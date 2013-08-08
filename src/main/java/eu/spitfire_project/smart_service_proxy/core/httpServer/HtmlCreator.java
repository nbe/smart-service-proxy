package eu.spitfire_project.smart_service_proxy.core.httpServer;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.SimpleSelector;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;

import eu.spitfire_project.smart_service_proxy.core.Backend;
import eu.spitfire_project.smart_service_proxy.core.UIElement;

public class HtmlCreator {
	
	public static final String latitudeURI = "http://www.w3.org/2003/01/geo/wgs84_pos#lat";
	public static final String longitudeURI = "http://www.w3.org/2003/01/geo/wgs84_pos#long";

	public static String getCssCommands(){
		StringBuilder buffer = new StringBuilder();
		buffer.append("<style>\n");
		buffer.append("body {\n");
		buffer.append("color:#FFFFFF;\n");  
		buffer.append("font-family:\"Arial\", sans-serif;\n");
		buffer.append("background:#808080 url('spitfire-logo.png') no-repeat right top;\n");
		buffer.append("}\n");

		buffer.append("a {text-decoration:none;}\n");
		buffer.append("a:link {color:#DD0000;}\n");
		buffer.append("a:visited {color:#AA0000;}\n");

		buffer.append("h1 {font-family:\"Verdana\", \"Arial\", sans-serif; padding:5px}\n");
		buffer.append("h2, h3, h4 {padding:5px}\n");
		buffer.append("p, ul {padding-left:40px}\n");

		buffer.append("hr {\n");
		buffer.append("width:70%;\n");
		buffer.append("height:2px;\n");
		buffer.append("background-color:#606060;\n");
		buffer.append("text-align:left;\n");
		buffer.append("margin-left:0;\n");
		buffer.append("border:none;\n");
		buffer.append("}\n");

		buffer.append("table {\n");
		buffer.append("width:85%;\n");
		buffer.append("border-collapse:collapse;\n");
		buffer.append("}\n");

		buffer.append("td, th {\n");
		buffer.append("border:1px solid #606060;\n");
		buffer.append("padding:2px 6px 2px 6px;\n");
		buffer.append("}\n");

		buffer.append("th {\n");
		buffer.append("font-size:110%;\n");
		buffer.append("text-align:left;\n");
		buffer.append("padding-top:6px;\n");
		buffer.append("padding-bottom:4px;\n");
		buffer.append("background-color:#707070;\n");
		buffer.append("}\n");

		buffer.append("td {\n");
		buffer.append("background-color:#909090;\n");
		buffer.append("}\n");

		buffer.append("tr.alt td {\n");
		buffer.append("background-color:#A0A0A0;\n");
		buffer.append("} <!-- mit <tr class=\"alt\"> nutzen -->\n");
		buffer.append("</style>\n");
		
		return buffer.toString();
	}
	
	public static String createMainPage(List<UIElement> operations, Map<URI, Backend> entities){
		StringBuilder buf = new StringBuilder();
        buf.append("<html>\n");
        buf.append("<head>\n");
        buf.append("<title>Smart Service Proxy</title>\n");
        buf.append(getCssCommands());
        buf.append("</head>\n");
        buf.append("<body>\n");
        buf.append("<h1>Smart Service Proxy</h1>\n");
        buf.append("<hr>\n");

        buf.append("<h2>Operations</h2>\n");
        buf.append("<ul>\n");
        for(UIElement elem: operations) {
        	buf.append(String.format("<li><a href=\"%s\">%s</a></li>\n", elem.getURI(), elem.getTitle()));
        }
        buf.append("</ul>\n");
        buf.append("<hr>\n");
        
        buf.append("<h2>Entities</h2>\n");
        buf.append("<ul>\n");
        for(Map.Entry<URI, Backend> entry: entities.entrySet()) {
            buf.append(String.format("<li><a href=\"%s\">%s</a>&nbsp;<a href=\"%s?html\">html</a></li>\n", entry.getKey(), entry.getKey(), entry.getKey()));
        }
        buf.append("</ul>\n");

        buf.append("</body>\n");
        buf.append("</html>\n");
        
        return buf.toString();
	}

	private static String screenText(RDFNode object, HashMap<String, String> aliases){
		String objectString = object.toString();
		if (object instanceof Resource){
			Resource objectAsResource =(Resource) object;
			try {
				objectAsResource.getId();
			} catch (Exception e){
				return objectString;
			}
			
			// no exception trying this => node is internal and needs an alias for our html table
			String currentAlias = aliases.get(objectString);
			if (currentAlias == null){
				aliases.put(objectString, "A" + aliases.size());
				objectString = aliases.get(objectString);
			} else {
				objectString = currentAlias;
			}
		}
		
		return objectString;
	}
	
	public static String createRDFTable(Model model){
		StringBuilder buf = new StringBuilder();
		
		buf.append("<table>\n");
		buf.append("<tr>\n");
		buf.append("<th>Subject</th>\n");
		buf.append("<th>Property</th>\n");
		buf.append("<th>Object</th>\n");
		buf.append("</tr>\n");
		
		StmtIterator iterator = model.listStatements();
		int i = 0;
		HashMap<String, String> aliases = new HashMap<String, String>();
		
		// print out the predicate, subject and object of each statement
		while (iterator.hasNext()) {
		    Statement stmt = iterator.nextStatement();

		    if (i % 2 == 0){
		    	buf.append("<tr>\n");
			} else {
				buf.append("<tr class=\"alt\">\n");
			}
		    String subject = screenText(stmt.getSubject(), aliases);
		    if (!subject.equals(stmt.getSubject().toString())){
		    	buf.append("<td><i>" + subject + "</i></td>\n");
			} else {
		    	buf.append("<td>" + subject + "</td>\n");
			}
			
		    buf.append("<td>" + stmt.getPredicate() + "</td>\n");
			
			String object = screenText(stmt.getObject(), aliases);
		    if (!object.equals(stmt.getObject().toString())){
		    	buf.append("<td><i>" + object + "</i></td>\n");
			} else {
				// format: value^^datatype
				if (object.contains("^^")){
					object = object.substring(0, object.indexOf("^^"));
				}
				
		    	buf.append("<td>" + object + "</td>\n");
			}
			buf.append("</tr>\n");
			
			i++;
		} 

		buf.append("</table>\n");
		
		return buf.toString();
	}
	
	public static boolean hasLocation(Model model){
		SimpleSelector selection = new SimpleSelector(null, null, (RDFNode) null) {
	          public boolean selects(Statement s) {
	              return (s.getPredicate().toString().equals(latitudeURI)
	            		  || s.getPredicate().toString().equals(longitudeURI));
		          }
		 };
		
		StmtIterator iterator = model.listStatements(selection);
		
		return iterator.hasNext();
	}
	
	public static String createLocationDisplay(Model model){
		StringBuilder buf = new StringBuilder();
		String lat = "52";
		String lon = "10";
		
		SimpleSelector selection = new SimpleSelector(null, null, (RDFNode) null) {
	          public boolean selects(Statement s) {
	              return (s.getPredicate().toString().equals(latitudeURI)
	            		  || s.getPredicate().toString().equals(longitudeURI));
		          }
		 };
		
		StmtIterator iterator = model.listStatements(selection);
		
		while (iterator.hasNext()){
			Statement stmt = iterator.next();
			String value = stmt.getObject().toString();
			value = value.substring(0, value.indexOf("^^"));
			
			if (stmt.getPredicate().toString().equals(latitudeURI)){
				lat = value;
			} else {
				lon = value;
			}
		}

		String googleMapsLink = "https://maps.google.com/maps?hl=en&amp;q=loc:" + lat + "+" + lon + "&amp;ie=UTF8&amp;t=m&amp;z=14&amp;iwloc=near";
		
		buf.append("<iframe width=\"480\" height=\"360\" frameborder=\"0\" scrolling=\"no\" " +
				"marginheight=\"0\" marginwidth=\"0\" " +
				"src=\"" + googleMapsLink + "&amp;output=embed\"></iframe>");
		buf.append("<br><small><a href=\"" + googleMapsLink + 
				" style=\"text-align:left\">View Larger Map</a></small>");
		       
        return buf.toString();
	}
	
	public static String createModelPage(Model model, URI uri, String host){
		StringBuilder buf = new StringBuilder();
        buf.append("<html>\n");
        buf.append("<head>\n");
        buf.append("<title>Smart Service Proxy</title>\n");
        buf.append(getCssCommands());
        buf.append("</head>\n");
        buf.append("<body>\n");
        buf.append(String.format("<h1>RDF for %s</h1>\n", uri));
        buf.append("<hr>\n");
        
        buf.append("<h2>Sensor Information</h2>\n");
        buf.append(createRDFTable(model));
        buf.append("<hr>\n");
        
        if (hasLocation(model)){
        	buf.append("<h2>Location</h2>\n");
            buf.append(createLocationDisplay(model));
        	buf.append("<hr>\n");
        }
        
        buf.append("<h2>Raw RDF Data</h2>\n");
        buf.append("<p>\n");
        buf.append(String.format("<a href=\"%s\">%s</a>\n", "http://" + host + uri, "http://" + host + uri));
        buf.append("</p>\n");
        buf.append("<hr>\n");
        
        buf.append("\n");
        buf.append(String.format("<h4><a href=\"%s\">return to SSP</a></h4>\n", "http://" + host));
        
        // TODO integrate into more backends

        buf.append("</body>\n");
        buf.append("</html>\n");
        
        return buf.toString();
	}
	
}
