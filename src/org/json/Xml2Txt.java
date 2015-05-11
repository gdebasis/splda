// Strips off an xml string into plain text
//
package org.json;
import java.io.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;
import javax.xml.parsers.*;
import java.util.*;
import javax.xml.transform.*;

class Xml2Txt extends DefaultHandler {
	StringBuffer buff;		// Accumulation buffer for storing the current topic
	String       xmlBody;

	Xml2Txt(String xmlBody) throws SAXException {
       this.xmlBody = xmlBody;
	   buff = new StringBuffer();
	}

	public void parse() throws Exception {
		SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
		saxParserFactory.setValidating(false);
		SAXParser saxParser = saxParserFactory.newSAXParser();
		saxParser.parse(new InputSource(new StringReader(xmlBody)), this);
	}

	public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
	}

	public void endElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
	}

	public void characters(char ch[], int start, int length) throws SAXException {
		buff.append(new String(ch, start, length));
	}

	String getText() {
		return buff.toString();
	}

	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("usage: java Xml2Txt <xml text>");
			return;
		}

		try {
			//Xml2Txt xml2txt = new Xml2Txt(args[0]);
			String noHTMLString = args[0].replaceAll("\\<.*?>","");
			//xml2txt.parse();
			//System.out.println(xml2txt.getText());
			System.out.println(noHTMLString);
		}
		catch (Exception ex) {
			ex.printStackTrace();
		}
	}
}
