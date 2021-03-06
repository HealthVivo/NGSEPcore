/*******************************************************************************
 * NGSEP - Next Generation Sequencing Experience Platform
 * Copyright 2016 Jorge Duitama
 *
 * This file is part of NGSEP.
 *
 *     NGSEP is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     NGSEP is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with NGSEP.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package ngsep.benchmark;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ngsep.genome.GenomicRegionComparator;
import ngsep.genome.ReferenceGenome;
import ngsep.math.LogMath;
import ngsep.math.PhredScoreHelper;
import ngsep.sequences.QualifiedSequenceList;
import ngsep.variants.CalledGenomicVariant;
import ngsep.variants.CalledGenomicVariantImpl;
import ngsep.variants.GenomicVariant;
import ngsep.variants.GenomicVariantImpl;
import ngsep.variants.VariantCallReport;
import ngsep.vcf.VCFFileReader;
import ngsep.vcf.VCFFileWriter;
import ngsep.vcf.VCFRecord;

/**
 * Compares a gold standard VCF or gVCF with a VCF file with predicted calls for the same sample  
 * @author Jorge Duitama
 *
 */
public class VCFGoldStandardComparator {
	private ReferenceGenome genome;
	private String outVCF = null;
	private int mode = 0;
	private short minQuality = 0;
	private int posPrint = -1;

	VCFFileWriter writer = new VCFFileWriter();
	public static void main(String[] args) throws Exception {
		VCFGoldStandardComparator instance = new VCFGoldStandardComparator();
		String referenceFile = args[0];
		String file1 = args[1];
		String file2 = args[2];
		
		if(args.length>5) {
			instance.outVCF = args[3];
			instance.mode = Integer.parseInt(args[4]);
			instance.minQuality = Short.parseShort(args[5]);
		}
		
		instance.genome = new ReferenceGenome(referenceFile);
		instance.compareFiles(file1,file2);
				
	}

	public void compareFiles(String vcfGS, String vcfTest) throws IOException {
		QualifiedSequenceList sequenceNames = genome.getSequencesMetadata();
		GenomicRegionComparator compRegion = new GenomicRegionComparator(sequenceNames);
		Map<Byte, GoldStandardComparisonCounts> countsPerType = new HashMap<>();
		countsPerType.put(GenomicVariant.TYPE_BIALLELIC_SNV, new GoldStandardComparisonCounts());
		countsPerType.put(GenomicVariant.TYPE_INDEL, new GoldStandardComparisonCounts());
		countsPerType.put(GenomicVariant.TYPE_STR, new GoldStandardComparisonCounts());
		long confidenceRegionsLength = 0;
		//countsPerType.put(GenomicVariant.TYPE_UNDETERMINED, new GoldStandardComparisonCounts());
		List<CalledGenomicVariant> callsTest = VCFFileReader.loadCalledVariantsSingleIndividualVCF(vcfTest);
		int lastRowCounts = GoldStandardComparisonCounts.NUM_ROWS_COUNTS-1;
		PrintStream out = null;
		try (VCFFileReader inGS = new VCFFileReader(vcfGS)){ 
			QualifiedSequenceList seqNames = genome.getSequencesMetadata();
			inGS.setSequences(seqNames);
			inGS.setLoadMode(VCFFileReader.LOAD_MODE_MINIMAL);
			if(outVCF!=null) {
				out = new PrintStream(outVCF);
				writer.printHeader(inGS.getHeader(), out);
			}
			Iterator<VCFRecord> itGS = inGS.iterator();
			int idxTest = 0;
			while (itGS.hasNext()) {
				VCFRecord rGS = itGS.next();
				CalledGenomicVariant callGS = rGS.getCalls().get(0);
				if(!callGS.isUndecided())confidenceRegionsLength+=callGS.getReference().length();
				boolean referenceRegion = callGS.isHomozygousReference();
				byte typeGS = loadType(callGS);
				List<CalledGenomicVariant> callsIntersect = new ArrayList<>();
				while(idxTest<callsTest.size()) {
					CalledGenomicVariant callTest = callsTest.get(idxTest);
					if(callTest.isUndecided()) {
						idxTest++;
						continue;
					}
					short qualTest = loadGenotypeQuality(callTest);
					int lastBefore = (idxTest == 0 || !callsTest.get(idxTest-1).getSequenceName().equals(callTest.getSequenceName()))? 0:callsTest.get(idxTest-1).getLast();
					int firstAfter = (idxTest == callsTest.size()-1 || !callsTest.get(idxTest+1).getSequenceName().equals(callTest.getSequenceName()))? seqNames.get(callTest.getSequenceName()).getLength():callsTest.get(idxTest+1).getFirst();
					
					byte type = loadType(callTest);
					int column = getGenotypeNumber(callTest);
					CalledGenomicVariant expandedCallTest = expandReferenceIndels(callTest,lastBefore,firstAfter);
					int cmp = compRegion.compare(callGS, expandedCallTest);
					if(posPrint==callGS.getFirst()) System.out.println("Call GS: "+callGS.getFirst()+"-"+callGS.getLast()+" call test: "+callTest.getFirst()+"-"+callTest.getLast()+" expanded call: "+expandedCallTest.getFirst()+"-"+expandedCallTest.getLast()+"type: "+type+" column: "+column+" comparison: "+cmp);					
					if (cmp<-1) break;
					if (cmp<=1) {
						//Overlap between GS and extended region
						if(!referenceRegion) {
							callsIntersect.add(expandedCallTest);
							idxTest++;
							continue;
						}
						//Variant falling out of reference region will be processed by the next gs region
						int remainder = expandedCallTest.getLast() - callGS.getLast();	
						if(posPrint==callGS.getFirst()) System.out.println("Call GS: "+callGS.getFirst()+"-"+callGS.getLast()+" call test: "+callTest.getFirst()+"-"+callTest.getLast()+" remainder: "+remainder);
						if(!callTest.isHomozygousReference() && remainder>0) break;
						if(callTest.getFirst()<callGS.getFirst() || callGS.getLast()<callTest.getLast()) column+=12;
					} else column+=12;
					countsPerType.get(type).update(0,Math.min(qualTest/10, lastRowCounts),column);
					VCFRecord record = new VCFRecord(callTest, VCFRecord.DEF_FORMAT_ARRAY_QUALITY, callTest, null);
					if(mode == 2 && qualTest>=minQuality && column < 12 && !callTest.isHomozygousReference()) writer.printVCFRecord(record, out);
					idxTest++;
				}
				if(posPrint==callGS.getFirst()) System.out.println("GS call at "+callGS.getSequenceName()+":"+callGS.getFirst()+" type: "+typeGS+" undecided: "+callGS.isUndecided()+" Calls intersect: "+callsIntersect.size()+" next test call: "+callsTest.get(idxTest).getFirst());
				if(callGS.isUndecided() || referenceRegion ) {
					//Ignore variants that intersect with unknown gold standard sites
					continue;
				}
				//Process non-reference call in the gold standard
				int n1 = getGenotypeNumber(callGS);
				if(callsIntersect.size()==0) {
					//Gold standard variant not found
					countsPerType.get(typeGS).update(0,lastRowCounts,9+n1);
					if(mode == 1) writer.printVCFRecord(rGS, out);
					continue;
				}
				boolean covered = false;
				for(CalledGenomicVariant callTest:callsIntersect) {
					boolean consistent = isConsistent(callGS, callTest);
					byte typeTest = loadType(callTest);
					short qualTest = callTest.getGenotypeQuality();
					int n2 = getGenotypeNumber(callTest);
					VCFRecord rTest = new VCFRecord(callTest, VCFRecord.DEF_FORMAT_ARRAY_QUALITY, callTest, null);
					if(consistent) {
						GoldStandardComparisonCounts counts = countsPerType.get(typeGS);
						int k = 3*n1+n2;
						int row = Math.min(qualTest/10, lastRowCounts); 
						counts.update(0,row,k);
						counts.update(row+1,lastRowCounts,9+n1);
						if(mode == 1 &&  qualTest<minQuality) {
							writer.printVCFRecord(rGS, out);
							writer.printVCFRecord(rTest, out);
						}
						if(mode==3 && n1!=n2 &&  qualTest>=minQuality) {
							writer.printVCFRecord(rGS, out);
							writer.printVCFRecord(rTest, out);
						}
						covered= true;
					} else {
						//False positive for inconsistent variant
						countsPerType.get(typeTest).update(0,Math.min(qualTest/10, lastRowCounts),12+n2);
						if(mode==3 && qualTest>=minQuality) {
							writer.printVCFRecord(rGS, out);
							writer.printVCFRecord(rTest, out);
						}
					}
				}
				if(!covered) {
					GoldStandardComparisonCounts counts = countsPerType.get(typeGS);
					//False negative for GS not properly called
					counts.update(0,lastRowCounts,9+n1);	
				}
			}
			while(idxTest<callsTest.size()) {
				//Process test variants at the end
				CalledGenomicVariant callTest = callsTest.get(idxTest);
				if(!callTest.isUndecided()) {
					byte typeTest = loadType(callTest);
					short qualTest = loadGenotypeQuality(callTest);
					int n2 = getGenotypeNumber(callTest);
					countsPerType.get(typeTest).update(0,Math.min(qualTest/10, lastRowCounts),12+n2);
					VCFRecord rTest = new VCFRecord(callTest, VCFRecord.DEF_FORMAT_ARRAY_QUALITY, callTest, null);
					if(mode == 2 && qualTest>=minQuality && !callTest.isHomozygousReference()) writer.printVCFRecord(rTest, out);
				}
				idxTest++;
			}
		} finally {
			if(out!=null) {
				out.flush();
				out.close();
			}
		}
		double confidentMbp = confidenceRegionsLength/1000000;
		System.out.println("Confident MBP: "+confidentMbp);
		for(GoldStandardComparisonCounts counts:countsPerType.values()) counts.setConfidentMbp(confidentMbp);
		System.out.println("SNVs");
		countsPerType.get(GenomicVariant.TYPE_BIALLELIC_SNV).print(System.out);
		System.out.println("Indels");
		countsPerType.get(GenomicVariant.TYPE_INDEL).print(System.out);
		System.out.println("STRs/OTHER");
		countsPerType.get(GenomicVariant.TYPE_STR).print(System.out);
	}

	private CalledGenomicVariant expandReferenceIndels(CalledGenomicVariant callTest, int lastBefore, int firstAfter) {
		String [] alleles = callTest.getAlleles();
		if(alleles.length<2) return callTest;
		Set<Integer> lengths = new HashSet<>();
		for(int i=0;i<alleles.length;i++) lengths.add(alleles[i].length());
		if(lengths.size()==1 && lengths.iterator().next()==1) return callTest;
		//Add 1 reference bp at the end
		int refBefore = Math.max(callTest.getFirst()-5, lastBefore+2);
		
		CharSequence left = null;
		if(refBefore<callTest.getFirst()) left = genome.getReference(callTest.getSequenceName(), refBefore, callTest.getFirst()-1);
		int refAfter = Math.min(callTest.getLast()+5, firstAfter-2);
		CharSequence right = null;
		if(callTest.getLast()<refAfter) right = genome.getReference(callTest.getSequenceName(), callTest.getLast()+1,refAfter);
		if(left == null && right==null) return callTest;
		int first = callTest.getFirst();
		if(left!=null) first-=left.length();
		List<String> extendedAlleles = new ArrayList<>();
		for(int i=0;i<alleles.length;i++) {
			String a = alleles[i];
			if(left!=null) a= left+a;
			if(right!=null) a= a+right;
			extendedAlleles.add(a);
		}
		GenomicVariant newVariant = new GenomicVariantImpl(callTest.getSequenceName(), first, extendedAlleles);
		newVariant.setType(callTest.getType());
		newVariant.setVariantQS(callTest.getVariantQS());
		CalledGenomicVariantImpl answer = new CalledGenomicVariantImpl(newVariant, callTest.getIndexesCalledAlleles());
		answer.setGenotypeQuality(callTest.getGenotypeQuality());
		return answer;
	}

	public boolean isConsistent(CalledGenomicVariant callGS, CalledGenomicVariant callTest) {
		boolean consistent = true;
		List<String> allelesGS = buildExtendedAlleles(callGS, callTest.getFirst(), callTest.getLast());
		if(posPrint==callGS.getFirst()) System.out.println("AllelesGS "+allelesGS);
		
		
		String [] allelesTest = callTest.getAlleles();
		//Expected start of alternative alleles in reference alleles
		int offset = 0;
		if(callGS.getFirst()<callTest.getFirst()) offset = callTest.getFirst()-callGS.getFirst();
		String reference = allelesGS.get(0);
		if(offset>0 && offset<reference.length()) {
			reference = reference.substring(offset);
		}
		consistent = reference.startsWith(allelesTest[0]);
		if(!consistent) System.err.println("WARN: Inconsistent reference for comparison between "+callGS.getSequenceName()+":"+callGS.getFirst()+ " reference: "+reference+" and "+callTest.getSequenceName()+":"+callTest.getFirst()+" reference: "+allelesTest[0]+" offset: "+offset );
		for(int i=1;i<allelesTest.length && consistent;i++) {
			String alleleTest = allelesTest[i];
			if(posPrint==callGS.getFirst()) System.out.println("Next allele test "+alleleTest);
			boolean found = false;
			for(int j=1;j<allelesGS.size() && !found;j++) {
				String alleleGS = allelesGS.get(j);
				if(offset>0 && offset<alleleGS.length()) {
					alleleGS = alleleGS.substring(offset);
				}
				found = alleleGS.startsWith(alleleTest);
			}
			consistent = found;
		}
		return consistent;
	}
	
	private List<String> buildExtendedAlleles(GenomicVariant v, int firstTest, int lastTest) {
		String seqName = v.getSequenceName();
		String left = null;
		if(firstTest<v.getFirst()) left = genome.getReference(seqName, firstTest, v.getFirst()-1).toString();
		String right = null;
		if(v.getLast()<lastTest) right = genome.getReference(seqName, v.getLast()+1, lastTest).toString();
		List<String> answer = new ArrayList<>();
		String [] alleles = v.getAlleles();
		for(int i=0;i<alleles.length;i++) {
			String allele = alleles[i];
			String allO = "";
			if(left!=null) allO += left;
			allO+=allele;
			if(right!=null) allO += right;
			answer.add(allO.toUpperCase());
		}
		return answer;
	}
	
	private byte loadType(CalledGenomicVariant call) {
		byte type = call.getType();
		if(type == GenomicVariant.TYPE_BIALLELIC_SNV) return type;
		if(type == GenomicVariant.TYPE_INDEL) return type;
		if(type == GenomicVariant.TYPE_STR) return type;
		String [] alleles = call.getAlleles();
		if(alleles.length==2 && alleles[0].length()!=alleles[1].length()) return GenomicVariant.TYPE_INDEL;
		else if(alleles.length==2 && alleles[0].length()==1) return GenomicVariant.TYPE_BIALLELIC_SNV;
		return GenomicVariant.TYPE_STR;
	}

	private static int getGenotypeNumber(CalledGenomicVariant call) {
		if(call.isHeterozygous()) return 1;
		else if (!call.isHomozygousReference()) return 2;
		return 0;
	}
	private short loadGenotypeQuality(CalledGenomicVariant call) {
		short q = call.getGenotypeQuality();
		if(q>0) return q;
		String [] alleles = call.getAlleles();
		String [] calledAlleles = call.getCalledAlleles();
		VariantCallReport report = call.getCallReport();
		if(report==null || calledAlleles.length==0 || !report.logConditionalsPresent()) return 0;
		double logP;
		Double sum=null;
		if(calledAlleles.length==1) logP = report.getLogConditionalProbability(calledAlleles[0], calledAlleles[0]);
		else logP = report.getLogConditionalProbability(calledAlleles[0], calledAlleles[1]);
		for(int i=0;i<alleles.length;i++) {
			for(int j=i;j<alleles.length;j++) {
				sum = LogMath.logSum(sum, report.getLogConditionalProbability(alleles[i], alleles[j]));
			}
		}
		double logPos = LogMath.logProduct(logP, -sum);
		double pos = LogMath.power10(logPos);
		q = PhredScoreHelper.calculatePhredScore(1-pos);
		call.setGenotypeQuality(q);
		//if(call.getFirst()==376) System.out.println("Conditional P: "+logP+" sum: "+sum+" logpos: "+logPos+" posterior: "+pos+" q: "+q);
		return q;
	}
}
class GoldStandardComparisonCounts {
	public static final int NUM_ROWS_COUNTS = 10;
	private int [][] counts;
	private static final DecimalFormat DF = new DecimalFormat("0.0000");
	private boolean countNonGSAsFP = false;
	private double confidentMbp=3000;
	public GoldStandardComparisonCounts () {
		counts = new int [NUM_ROWS_COUNTS][15];
	}
	
	
	/**
	 * @return the countNonGSAsFP
	 */
	public boolean isCountNonGSAsFP() {
		return countNonGSAsFP;
	}


	/**
	 * @param countNonGSAsFP the countNonGSAsFP to set
	 */
	public void setCountNonGSAsFP(boolean countNonGSAsFP) {
		this.countNonGSAsFP = countNonGSAsFP;
	}
	
	/**
	 * @return the confidentMbp
	 */
	public double getConfidentMbp() {
		return confidentMbp;
	}


	/**
	 * @param confidentMbp the confidentMbp to set
	 */
	public void setConfidentMbp(double confidentMbp) {
		this.confidentMbp = confidentMbp;
	}


	public void update(int firstRow, int lastRow, int column) {
		for(int i=firstRow;i<=lastRow;i++) {
			counts[i][column]++;
		}	
	}
	
	public void print(PrintStream out) {
		for (int i=0;i<counts.length; i++) {
			out.print(""+(i*10));
			for(int j=0;j<counts[0].length;j++) {
				out.print("\t"+counts[i][j]);
			}
			printComparisonStats(counts[i], out);
			out.println();
		}
	}

	private void printComparisonStats(int[] row, PrintStream out) {
		int gsTotal0 = row[0]+row[1]+row[2]+row[9];
		int gsTotal1 = row[3]+row[4]+row[5]+row[10];
		int gsTotal2 = row[6]+row[7]+row[8]+row[11];
		out.print("\t"+gsTotal0+"\t"+gsTotal1+"\t"+gsTotal2);
		
		int testTotal0 = row[0]+row[3]+row[6]+row[12];
		int testTotal1 = row[1]+row[4]+row[7]+row[13];
		int testTotal2 = row[2]+row[5]+row[8]+row[14];
		out.print("\t"+testTotal0+"\t"+testTotal1+"\t"+testTotal2);
		
		double recall1 = 0;
		if(gsTotal1>0) {
			recall1 = (double)row[4] / gsTotal1;
		}
		int fd1 = row[1] + row [7];
		double denomFDR1 = testTotal1;
		if(countNonGSAsFP) fd1 += row[13];
		else denomFDR1 -= row[13];
		double fppm1 = fd1/confidentMbp;
		double fdr1 = 0;
		double precision1 = 1;
		if(denomFDR1>1) {
			fdr1 = (double)fd1 / denomFDR1;
			precision1 = (double)row[4] / denomFDR1;
		}
		double f1 = 0;
		if(precision1 + recall1 > 0) {
			f1 = 2.0*precision1*recall1/(precision1+recall1);
		}
		
		out.print("\t"+DF.format(recall1)+"\t"+fd1+"\t"+DF.format(fppm1)+"\t"+DF.format(fdr1)+"\t"+DF.format(precision1)+"\t"+DF.format(f1));
		
		double recall2 = 0;
		if(gsTotal2>0) {
			recall2 = (double)row[8] / gsTotal2;
		}
		int fd2 = row[2] + row [5];
		double denomFDR2 = testTotal2;
		if(countNonGSAsFP) fd2 += row[14];
		else denomFDR2 -= row[14];
		double fppm2 = fd2/confidentMbp;
		double fdr2 = 0;
		double precision2 = 1;
		if(denomFDR2>0) {
			fdr2 = (double)fd2 / denomFDR2;
			precision2 = (double)row[8] / denomFDR2;
		}
		double f2 = 0;
		if(precision2 + recall2 > 0) {
			f2 = 2.0*precision2*recall2/(precision2+recall2);
		}
		out.print("\t"+DF.format(recall2)+"\t"+fd2+"\t"+DF.format(fppm2)+"\t"+DF.format(fdr2)+"\t"+DF.format(precision2)+"\t"+DF.format(f2));
		
	}
	
}
