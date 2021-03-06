/*******************************************************************************
 *  Imixs IX Workflow Technology
 *  Copyright (C) 2001, 2008 Imixs Software Solutions GmbH,  
 *  http://www.imixs.com
 *  
 *  This program is free software; you can redistribute it and/or 
 *  modify it under the terms of the GNU General Public License 
 *  as published by the Free Software Foundation; either version 2 
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful, 
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of 
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
 *  General Public License for more details.
 *  
 *  You can receive a copy of the GNU General Public
 *  License at http://www.gnu.org/licenses/gpl.html
 *  
 *  Contributors:  
 *  	Imixs Software Solutions GmbH - initial API and implementation
 *  	Ralph Soika
 *******************************************************************************/
package org.imixs.microservice;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.Vector;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RunAs;
import javax.ejb.EJB;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.ParserConfigurationException;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.bpmn.BPMNModel;
import org.imixs.workflow.bpmn.BPMNParser;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.engine.ModelService;
import org.imixs.workflow.exceptions.AccessDeniedException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.xml.XMLDataCollection;
import org.imixs.workflow.xml.XMLDocument;
import org.imixs.workflow.xml.XMLDocumentAdapter;
import org.xml.sax.SAXException;

/**
 * The SetupService EJB initializes the Imxis-Microservice settings by its
 * method 'init()'.
 * <p>
 * The Imixs Microservice loads a default model defined by the environment
 * variable 'MODEL'. This variable can point to multiple model resources
 * separated by a ';'. A model resource file must have the file extension
 * '.bpmn'.
 * <p>
 * Optional it is also possible to provide setup data in a XML file.
 * 
 * 
 * 
 * @author rsoika
 * 
 */
@DeclareRoles({ "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@RunAs("org.imixs.ACCESSLEVEL.MANAGERACCESS")
@Startup
@Singleton
@Path("/setup")
public class SetupService {
	public static String SETUP_OK = "OK";
	public static String MODEL_INITIALIZED = "MODEL_INITIALIZED";

	private static Logger logger = Logger.getLogger(SetupService.class.getName());

	@EJB
	DocumentService documentService;

	@EJB
	ModelService modelService;

	/**
	 * This method start the system setup during deployment
	 * 
	 * @throws AccessDeniedException
	 */
	@PostConstruct
	public void startup() {
		logger.info("...setup service started...");
		init();
	}

	/**
	 * This method loads the default model
	 * 
	 * @return - status
	 */
	@GET
	public String init() {
		logger.finest("......scan default models...");

		String sModels = getEnv(ImixsApplication.ENV_IMIXS_MODEL, null);

		String[] modelResources = sModels.split(";");

		for (String modelResource : modelResources) {

			// try to load this model

			// test if bpmn model?
			if (modelResource.endsWith(".bpmn") || modelResource.endsWith(".xml")) {
				logger.info("...uploading default model file: '" + modelResource + "'....");
				// if resource starts with '/' then we pickp the file form the filesystem.
				// otherwise we load it as a resource bundle.
				InputStream inputStream = null;
				try {
					if (modelResource.startsWith("/")) {
						File initialFile = new File(modelResource);
						inputStream = new FileInputStream(initialFile);
					} else {
						inputStream = ImixsApplication.class.getClassLoader().getResourceAsStream(modelResource);
					}
					// parse model file....

					ByteArrayOutputStream bos = new ByteArrayOutputStream();
					int next;

					next = inputStream.read();
					while (next > -1) {
						bos.write(next);
						next = inputStream.read();
					}
					bos.flush();
					byte[] result = bos.toByteArray();

					// is BPMN?
					if (modelResource.endsWith(".bpmn")) {
						BPMNModel model = BPMNParser.parseModel(result, "UTF-8");
						modelService.saveModel(model);
					} else {
						// XML
						importXmlEntityData(result);
					}

					return MODEL_INITIALIZED;
				} catch (IOException | ModelException | ParseException | ParserConfigurationException
						| SAXException e) {
					logger.severe(
							"unable to load model configuration - please check imixs.properties file for key 'setup.defaultModel'");
					throw new RuntimeException(
							"loadDefaultModels - unable to load model configuration - please check imixs.properties file for key 'setup.defaultModel'");
				} finally {
					if (inputStream!=null) {
						try {
							inputStream.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}

			} else {
				logger.severe("Wrong model format: '" + modelResource + "' - expected *.bpmn or *.xml");
			}

		}
		return SETUP_OK;

	}

	/**
	 * this method imports an xml entity data stream. This is used to provide model
	 * uploads during the system setup. The method can also import general entity
	 * data like configuration data.
	 * 
	 * @param event
	 * @throws Exception
	 */
	public void importXmlEntityData(byte[] filestream) {
		XMLDocument entity;
		ItemCollection itemCollection;
		String sModelVersion = null;

		if (filestream == null)
			return;
		try {

			XMLDataCollection ecol = null;
			logger.fine("importXmlEntityData - importModel, verifing file content....");

			JAXBContext context;
			Object jaxbObject = null;
			// unmarshall the model file
			ByteArrayInputStream input = new ByteArrayInputStream(filestream);
			try {
				context = JAXBContext.newInstance(XMLDataCollection.class);
				Unmarshaller m = context.createUnmarshaller();
				jaxbObject = m.unmarshal(input);
			} catch (JAXBException e) {
				throw new ModelException(ModelException.INVALID_MODEL,
						"error - wrong xml file format - unable to import model file: ", e);
			}
			if (jaxbObject == null)
				throw new ModelException(ModelException.INVALID_MODEL,
						"error - wrong xml file format - unable to import model file!");

			ecol = (XMLDataCollection) jaxbObject;
			// import the model entities....
			if (ecol.getDocument().length > 0) {

				Vector<String> vModelVersions = new Vector<String>();
				// first iterrate over all enttity and find if model entries are
				// included
				for (XMLDocument aentity : ecol.getDocument()) {
					itemCollection = XMLDocumentAdapter.putDocument(aentity);
					// test if this is a model entry
					// (type=WorkflowEnvironmentEntity)
					if ("WorkflowEnvironmentEntity".equals(itemCollection.getItemValueString("type"))
							&& "environment.profile".equals(itemCollection.getItemValueString("txtName"))) {

						sModelVersion = itemCollection.getItemValueString("$ModelVersion");
						if (vModelVersions.indexOf(sModelVersion) == -1)
							vModelVersions.add(sModelVersion);
					}
				}
				// now remove old model entries....
				for (String aModelVersion : vModelVersions) {
					logger.fine("importXmlEntityData - removing existing configuration for model version '"
							+ aModelVersion + "'");
					modelService.removeModel(aModelVersion);
				}
				// save new entities into database and update modelversion.....
				for (int i = 0; i < ecol.getDocument().length; i++) {
					entity = ecol.getDocument()[i];
					itemCollection = XMLDocumentAdapter.putDocument(entity);
					// save entity
					documentService.save(itemCollection);
				}

				logger.fine("importXmlEntityData - " + ecol.getDocument().length + " entries sucessfull imported");
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	/**
	 * Returns a environment variable. An environment variable can be provided as a
	 * System property.
	 * 
	 * @param env
	 *            - environment variable name
	 * @param defaultValue
	 *            - optional default value
	 * @return value
	 */
	public static String getEnv(String env, String defaultValue) {
		logger.finest("......read env: " + env);
		String result = System.getenv(env);
		if (result == null || result.isEmpty()) {
			result = defaultValue;
		}
		return result;
	}

}