/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.request;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Iterator;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.search.Searcher;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.search.DocSlice;
import org.apache.solr.update.DocumentBuilder;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;

/**
 * <p> A response writer that uses velocity template for response creation.
 *     Possible request parameters: </p>
 * <ul>
 *     <li> 
 *         <b>vl.template:</b> 
 *     	   The name of the template file without .vm suffix. </li>
 *     <li> 
 *         <b>vl.json:</b> 
 *         A name of a Javascript method. If set, the response is wrapped into this object. 
 *         useful for JSON requests. 
 *     </li>
 *     <li> 
 *         <b>vl.content:</b> 
 *         Specify a custom content type for the response. Default is "text/html" for standard requests,
 *         and "text/x-json" for JSON requests.  
 *     </li>
 *     <li> 
 *         <b>vl.response:</b> 
 *         To provide an implementation of  {@link SolrResponse} inside the template,
 *     	   specify the class name of the implementation. For convenience, it looks 
 *         inside the package "org.apache.solr.client.solrj.response", so you only 
 *         have to choose  eg. <i>QueryResponse, LikeResponse, MultiCoreResponse</i>.
 *     	   Custom classes may be accessed using the full qualified class name, 
 *         eg. <i>my.custom.package.CustomResponse</i>	
 *     </li>
 * </ul>
 */
public class VelocityResponseWriter implements QueryResponseWriter {

	private static final String PARAMETER_TEMPLATE="vl.template";
	private static final String PARAMETER_JSON="vl.json";
	private static final String PARAMETER_RESPONSE="vl.response";
	private static final String PARAMETER_CONTENT_TYPE="vl.content";
	
	public void write(Writer writer, SolrQueryRequest request,
			SolrQueryResponse response) throws IOException {
		
		// init velocity and get template
		VelocityEngine engine = new VelocityEngine();
		File baseDir = new File(request.getCore().getResourceLoader().getConfigDir(), "velocity");
		engine.setProperty(VelocityEngine.FILE_RESOURCE_LOADER_PATH, baseDir.getAbsolutePath());
		engine.setProperty(VelocityEngine.RESOURCE_LOADER, "file");
		Template template;
		try {
			template = engine.getTemplate(request.getParams().get(PARAMETER_TEMPLATE, "default") + ".vm");
		} catch (Exception e) {
			throw new IOException(e.getMessage());
		}
		
		// put raw response into context
		VelocityContext context = new VelocityContext();
		context.put("rawResponse", new RawResponseHelper(request, response));
		
		// convert response if a class is specified
		if (request.getParams().get(PARAMETER_RESPONSE) != null) {
			String className = request.getParams().get(PARAMETER_RESPONSE);
			
			// create SolrResponse using reflection
			SolrResponse solrResponse;
			Object object;
			try {
				object = request.getCore().getResourceLoader().newInstance(className, "client.solrj.response.");
			} catch (RuntimeException e) {
				throw new IOException("Unable to resolve response class \"" + className + "\": " + e.getMessage());
			}
			if (!(object instanceof SolrResponse)) {
				throw new IOException("Class \"" + className + "\" doesn't implement SolrResponse!");
			}
			solrResponse = (SolrResponse) object;
			
			// inject the request into the response
			solrResponse.setResponse(new EmbeddedSolrServer(request.getCore()).getParsedResponse(request, response));
			
			// put it into the context
			context.put("response", solrResponse);
		}
		
		// create output, optionally wrap it into a json object
		if (isWrappedResponse(request)) {
			StringWriter stringWriter = new StringWriter();
			template.merge(context, stringWriter);
			writer.write(request.getParams().get(PARAMETER_JSON) + "(");
			writer.write(getJSONWrap(stringWriter.toString()));
			writer.write(')');
		} else {
			template.merge(context, writer);
		}

	}

	public String getContentType(SolrQueryRequest request,
			SolrQueryResponse response) {
		if (request.getParams().get(PARAMETER_CONTENT_TYPE) != null) {
			return request.getParams().get(PARAMETER_CONTENT_TYPE);
		}
		if (isWrappedResponse(request)) {
			return JSONResponseWriter.CONTENT_TYPE_JSON_UTF8;
		}
		return "text/html";
	}

	public void init(NamedList args) {
		// TODO
	}
	
	private boolean isWrappedResponse(SolrQueryRequest request) {
		return request.getParams().get(PARAMETER_JSON) != null;
	}
	
	public String getJSONWrap(String xmlResult) {
		// escape the double quotes and backslashes
		String replace1 = xmlResult.replaceAll("\\\\", "\\\\\\\\");
		replace1 = replace1.replaceAll("\\n", "\\\\n");
		replace1 = replace1.replaceAll("\\r", "\\\\r");
		String replaced = replace1.replaceAll("\"", "\\\\\"");
		// wrap it in a JSON object
		return "{\"result\":\"" + replaced + "\"}";
	}

	/**
	 * A helper class that provides convenient methods for the raw solr response.
	 */
	public class RawResponseHelper {

		private Searcher searcher;
		private SolrQueryResponse response;
		private SolrQueryRequest request;

		public RawResponseHelper(SolrQueryRequest request,
				SolrQueryResponse response) {
			this.searcher = request.getSearcher();
			this.response = response;
			this.request = request;
		}

		public Iterator<SolrDocument> getResultIterator() {
			final Iterator<Integer> iterator = ((DocSlice) response.getValues()
					.get("response")).iterator();
			return new Iterator<SolrDocument>() {

				public boolean hasNext() {
					return iterator.hasNext();
				}

				public SolrDocument next() {
					Document document = null;
					SolrDocument solrDocument = new SolrDocument();
					try {
						document = searcher.doc(iterator.next());
						new DocumentBuilder(request.getSchema()).loadStoredFields(solrDocument, document);
					} catch (CorruptIndexException e) {
						throw new RuntimeException("Error converting lucene document into solr document!");
					} catch (IOException e) {
						throw new RuntimeException("Error converting lucene document into solr document!");
					}
					
					return solrDocument;
				}

				public void remove() {

				}

			};
		}
		
		public String getRequestParameter(String param) {
			return request.getParams().get(param);
		}
		
		public SolrQueryRequest getRequest() {
			return request;
		}
		
		public SolrQueryResponse getResponse() {
			return response;
		}
		
		
	}
	
	
}