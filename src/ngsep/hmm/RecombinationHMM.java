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
package ngsep.hmm;

import java.util.List;
import java.util.Random;

public class RecombinationHMM extends VariableTransitionHMM {
	public RecombinationHMM(List<? extends HMMState> states, int numMarkers) {
		super(states,numMarkers);
	}

	public void calculateTransitions(List<Integer> positions, double avgCMPerKbp, boolean randomize) {
		int m = getSteps();
		if(m!=positions.size()) throw new IllegalArgumentException("Length of positions vector "+positions.size()+" is not consistent with the number of markers "+m);
		Random r = new Random();
		double [] recombinationProbabilities = new double[m-1];
		for(int i=0;i<m-1;i++) {
			double distance = positions.get(i+1)-positions.get(i);
			double dKbp = (distance)/1000.0;
			//Distance in morgans 
			double dMorgans = 0.01*avgCMPerKbp*dKbp;
			//Use Haldane's formula to estimate probability of crossover between the sites
			double recombP = 0.5*(1 - Math.exp(-2.0*dMorgans));
			if(randomize) recombP = r.nextGaussian()*0.01 + recombP;
			if(recombP > 0.5) recombP = 0.5;
			if(recombP <=0.001) recombP = 0.001;
			//System.out.println("Distance: "+distance+" recomb prob: "+recombP+ " dMorgans: "+dMorgans);
			
			recombinationProbabilities[i]=recombP;
		}
		calculateTransitions(recombinationProbabilities);
	}

	public void calculateTransitions(double[] recombinationProbabilities) {
		calculateUniformChangeTransitions(recombinationProbabilities);
	}
}
