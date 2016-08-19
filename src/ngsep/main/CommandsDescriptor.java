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
package ngsep.main;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class CommandsDescriptor {
	public static final String ATTRIBUTE_VERSION="version";
	public static final String ATTRIBUTE_DATE="date";
	public static final String ATTRIBUTE_ID="id";
	public static final String ATTRIBUTE_CLASSNAME="class";
	public static final String ATTRIBUTE_TYPE="type";
	public static final String ATTRIBUTE_DEFAULT="default";
	public static final String ATTRIBUTE_ATTRIBUTE="attribute";
	public static final String ELEMENT_COMMAND="command";
	public static final String ELEMENT_TITLE="title";
	public static final String ELEMENT_INTRO="intro";
	public static final String ELEMENT_DESCRIPTION="description";
	public static final String ELEMENT_USAGE="usage";
	public static final String ELEMENT_ARGUMENT="argument";
	public static final String ELEMENT_OPTION="option";
	
	private String resource = "/ngsep/main/CommandsDescriptor.xml";
	private String swVersion;
	private String releaseDate;
	private String swTitle;
	private Map<String,Command> commands = new TreeMap<String,Command>();
	private Map<String,Command> commandsByClass = new TreeMap<String,Command>();
	public static CommandsDescriptor instance = new CommandsDescriptor();
	private CommandsDescriptor () {
		load();
	}
	public static CommandsDescriptor getInstance() {
		return instance;
	}
	private void load() {
		InputStream is = null;
		Document doc;
		try {
			is = this.getClass().getResourceAsStream(resource);
			if(is==null) throw new RuntimeException("Commands descriptor can not be found");
			DocumentBuilder documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
			doc = documentBuilder.parse(new InputSource(is));
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			if(is!=null) {
				try {
					is.close();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
		Element rootElement = doc.getDocumentElement();
		swVersion = rootElement.getAttribute(ATTRIBUTE_VERSION);
		releaseDate = rootElement.getAttribute(ATTRIBUTE_DATE);
		loadSoftwareDescription(rootElement);
		
	}
	private void loadSoftwareDescription(Element parent) {
		NodeList offspring = parent.getChildNodes(); 
		for(int i=0;i<offspring.getLength();i++){  
			Node node = offspring.item(i);
			if (node instanceof Element){ 
				Element elem = (Element)node;
				if(ELEMENT_COMMAND.equals(elem.getNodeName())) {
					Command c;
					try {
						c = loadCommand(elem);
					} catch (RuntimeException e) {
						throw new RuntimeException("Can not load command with id "+elem.getAttribute(ATTRIBUTE_ID),e);
					}
					if(commands.containsKey(c.getId())) throw new RuntimeException("Duplicated command id: "+c.getId());
					commands.put(c.getId(), c);
					commandsByClass.put(c.getProgram().getName(), c);
				} else if(ELEMENT_TITLE.equals(elem.getNodeName())) {
					swTitle = loadText(elem);
				} 
			}
		}
		
	}
	private Command loadCommand(Element cmdElem) {
		String id = cmdElem.getAttribute(ATTRIBUTE_ID);
		if(id==null) throw new RuntimeException("Every command must have an id");
		String className = cmdElem.getAttribute(ATTRIBUTE_CLASSNAME);
		if(className==null) throw new RuntimeException("Every command must have a class name");
		Class<?> program;
		try {
			program = Class.forName(className);
			Class<?>[] argTypes = new Class[] { String[].class };
			program.getDeclaredMethod("main",argTypes);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException("Can not load class for command: "+id,e);
		} catch (NoSuchMethodException e) {
			throw new RuntimeException("Class "+className+" for command: "+id+" does not have a main method",e);
		} catch (SecurityException e) {
			throw new RuntimeException("Class "+className+" for command: "+id+" can not be called",e);
		}
		Command cmd = new Command(id,program);
		NodeList offspring = cmdElem.getChildNodes(); 
		for(int i=0;i<offspring.getLength();i++){  
			Node node = offspring.item(i);
			if (node instanceof Element){ 
				Element elem = (Element)node;
				if(ELEMENT_TITLE.equals(elem.getNodeName())) {
					cmd.setTitle(loadText(elem));
				} else if(ELEMENT_INTRO.equals(elem.getNodeName())) {
					cmd.setIntro(loadText(elem));
				} else if(ELEMENT_DESCRIPTION.equals(elem.getNodeName())) {
					cmd.setDescription(loadText(elem));
				} else if(ELEMENT_ARGUMENT.equals(elem.getNodeName())) {
					cmd.addArgument(loadText(elem));
				} else if(ELEMENT_OPTION.equals(elem.getNodeName())) {
					String optId = elem.getAttribute(ATTRIBUTE_ID);
					if(optId==null || optId.length()==0) throw new RuntimeException("Every option must have an id");
					CommandOption opt = new CommandOption(optId);
					String optType = elem.getAttribute(ATTRIBUTE_TYPE);
					if(optType!=null && optType.length()>0) opt.setType(optType);
					String optDefault = elem.getAttribute(ATTRIBUTE_DEFAULT);
					if(optDefault!=null && optDefault.trim().length()>0) opt.setDefaultValue(optDefault);
					String optAttribute = elem.getAttribute(ATTRIBUTE_ATTRIBUTE);
					if(optAttribute!=null && optAttribute.trim().length()>0) opt.setAttribute(optAttribute);
					String description = loadText(elem);
					if(description==null || description.length()==0) throw new RuntimeException("Option "+optId+" does not have a description");
					opt.setDescription(description);
					cmd.addOption(opt);
				}	
			}
		}
		return cmd;
	}
	private String loadText(Element elem) {
		NodeList offspring = elem.getChildNodes();
		for (int i=0; i < offspring.getLength(); i++) {
	        Node subnode = offspring.item(i);
	        if (subnode.getNodeType() == Node.TEXT_NODE) {
	            String desc = subnode.getNodeValue();
	            if(desc!=null) {
	            	desc = desc.trim();
	            	//return desc;
	            	return desc.replaceAll("\\s", " ");
	            }
	        }
		}
		return null;
	}
	public String getSwVersion() {
		return swVersion;
	}
	public String getReleaseDate() {
		return releaseDate;
	}
	public Command getCommand(String name) {
		return commands.get(name);
	}
	
	public void printUsage(){
		System.err.println();
		printVersionHeader();
		System.err.println("=============================================================================");
		System.err.println();
		System.err.println("USAGE: java -jar NGSEPcore_"+swVersion+".jar <MODULE> [options]");
		System.err.println();
		System.err.println("Modules:");
		System.err.println();
		for(String commandName:commands.keySet()) {
			Command c = commands.get(commandName);
			System.err.println("  > " + commandName);
			System.err.println("          "+c.getIntro());
		}
		
		System.err.println();
		System.err.println("See http://sourceforge.net/projects/ngsep/files/Library/ for more details.");
		System.err.println();		
	}
	private void printVersionHeader() {
		System.err.println(" NGSEP - "+swTitle);
		System.err.println(" Version " + swVersion + " ("+releaseDate+")");
	}
	public void printHelp(Class<?> program) {
		Command c = commandsByClass.get(program.getName());
		int titleLength = c.getTitle().length();
		for(int i=0;i<titleLength;i++)System.err.print("-");
		System.err.println();
		System.err.println(c.getTitle());
		for(int i=0;i<titleLength;i++)System.err.print("-");
		System.err.println();
		System.err.println();
		System.err.println(c.getDescription());
		System.err.println();
		System.err.println("USAGE:");
		System.err.println();
		System.err.print("java -jar NGSEPcore_"+swVersion+".jar "+ c.getId());
		for(String arg:c.getArguments()) System.err.print(" <"+arg+">");
		System.err.println();
		System.err.println();
		System.err.println("OPTIONS:");
		System.err.println();
		List<CommandOption> options = c.getOptionsList(); 
		int longerOpt = getLongerOption(options);
		for(CommandOption option:options) {
			System.err.print("        -"+option.getId());
			if(option.getType()!=null) System.err.print(" "+option.getType());
			int optLength = option.getPrintLength();
			int diff = longerOpt-optLength;
			for(int i=0;i<diff+1;i++)System.err.print(" ");
			System.err.print(": ");
			String desc = option.getDescription();
			if(option.getDefaultValue()!=null) desc+=" Default: "+option.getDefaultValue();
			printDescription(desc,longerOpt+3);
		}
		System.err.println();
	}
	
	private int getLongerOption(List<CommandOption> options) {
		int max = 0;
		for(CommandOption opt:options) {
			int l = opt.getPrintLength();
			if(max<l) max = l;
		}
		return max;
	}
	
	private void printDescription(String desc, int startColumn) {
		//TODO: Print in a command line friendly format
		System.err.println(desc);
		
	}
	public void printVersion() {
		System.err.println();
		printVersionHeader();
		System.err.println();
		System.err.println(" For usage type     java -jar NGSEPcore_"+swVersion+".jar --help");
		System.err.println();
		System.err.println(" For citing type     java -jar NGSEPcore_"+swVersion+".jar --citing");
		System.err.println();
	}
	
	public void printCiting(){
		System.err.println("------");
		System.err.println("Citing");
		System.err.println("------");
		System.err.println();
		System.err.println("To cite NGSEP, please include in your references the following manuscript:");
		System.err.println();
		System.err.println("Duitama J, Quintero JC, Cruz DF, Quintero C, Hubmann G, Foulquie-Moreno MR, Verstrepen KJ, Thevelein JM, and Tohme J. (2014).");
		System.err.println("An integrated framework for discovery and genotyping of genomic variants from high-throughput sequencing experiments.");
		System.err.println("Nucleic Acids Research. 42 (6): e44. doi: 10.1093/nar/gkt1381");
		System.err.println();
		System.err.println("See the README.txt file for papers describing the algorithms implemented in NGSEP and supporting packages");
		System.err.println();
	}
	
	public int loadOptions(Object programInstance, String [] args ) {
		if (args.length == 0 || args[0].equals("-h") ||args[0].equals("--help")){
			CommandsDescriptor.getInstance().printHelp(programInstance.getClass());
			return -1;
		}
		Command c = commandsByClass.get(programInstance.getClass().getName());
		int i = 0;
		while(i<args.length && args[i].charAt(0)=='-') {
			if("-".equals(args[i])) break;
			CommandOption o = c.getOption(args[i].substring(1));
			if (o==null) {
				System.err.println("Unrecognized option "+args[i]);
				CommandsDescriptor.getInstance().printHelp(programInstance.getClass());
				return -1;
			}
			Method setter = o.findSetMethod(programInstance);
			Object value;
			if(CommandOption.TYPE_BOOLEAN.equals(o.getType())) {
				value = true;
			} else {
				i++;
				value = o.decodeValue(args[i]);
			}
			try {
				setter.invoke(programInstance, value);
			} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
				System.err.println("Error setting value "+value+" for option "+o.getId()+" of type: "+o.getType()+". Error: "+e.getMessage());
				e.printStackTrace();
			}
			i++;
		}
		return i;
	}
	
}
