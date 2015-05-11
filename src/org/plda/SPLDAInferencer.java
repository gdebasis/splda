/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.plda;

import java.io.FileWriter;

/**
 *
 * @author dganguly
 */

public class SPLDAInferencer extends PLDAInferencer {

	public SPLDAInferencer(String estPropFile, String infPropFile) throws Exception {
        estimatedModel = new SPLDA(estPropFile);
        inferedModel = new SPLDA(infPropFile);
    }

    @Override
	public void infer() {
		super.infer();
		((SPLDA)inferedModel).predictResponses((SPLDA)estimatedModel);
	}
    
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: java SPLDAInferencer <estimate prop> <inference prop>");
            args = new String[2];
            args[0] = "init.properties";
            args[1] = "genqueries.properties";
        }
        
        try {
            SPLDAInferencer inferencer = new SPLDAInferencer(args[0], args[1]);
			inferencer.infer();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }    
}

