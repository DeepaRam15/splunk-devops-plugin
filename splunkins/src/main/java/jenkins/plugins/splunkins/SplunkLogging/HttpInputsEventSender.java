package jenkins.plugins.splunkins.SplunkLogging;


/**
 * @copyright
 *
 * Copyright 2013-2015 Splunk, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"): you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.logging.Logger;


/**
 * This is an internal helper class that sends logging events to Splunk http event collector.
 */
public class HttpInputsEventSender extends TimerTask{
    private final static Logger LOGGER = Logger.getLogger(HttpInputsEventSender.class.getName());
    public static final String MetadataTimeTag = "time";
    public static final String MetadataIndexTag = "index";
    public static final String MetadataSourceTag = "source";
    public static final String MetadataSourceTypeTag = "sourcetype";
    private static final String AuthorizationHeaderTag = "Authorization";
    private static final String AuthorizationHeaderScheme = "Splunk %s";
    private static final String HttpEventCollectorUriPath = "/services/collector/event/1.0";
    private static final String HttpContentType = "application/json; profile=urn:splunk:event:1.0; charset=utf-8";
    private static final String SendModeSequential = "sequential";
    private static final String SendModeSParallel = "parallel";

    /**
     * Sender operation mode. Parallel means that all HTTP requests are
     * asynchronous and may be indexed out of order. Sequential mode guarantees
     * sequential order of the indexed events.
     */
    public enum SendMode
    {
        Sequential,
        Parallel
    };

    private String url;
    private String token;
    private long maxEventsBatchCount;
    private long maxEventsBatchSize;
    private long retriesOnError;
    private Dictionary<String, String> metadata;
    private Timer timer;
    private List<HttpInputsEventInfo> eventsBatch = new LinkedList<HttpInputsEventInfo>();
    private long eventsBatchSize = 0; // estimated total size of events batch
    private CloseableHttpAsyncClient httpClient;
    private boolean disableCertificateValidation = false;
    private SendMode sendMode = SendMode.Sequential;

    /**
     * Initialize HttpEventCollectorSender
     * @param Url http event collector input server
     * @param token application token
     * @param delay batching delay
     * @param maxEventsBatchCount max number of events in a batch
     * @param maxEventsBatchSize max size of batch
     * @param metadata events metadata
     */
    public HttpInputsEventSender(
            final String Url, final String token,
            long delay, long maxEventsBatchCount, long maxEventsBatchSize,
            long retriesOnError,
            String sendModeStr,
            Dictionary<String, String> metadata) {
        this.url = Url + HttpEventCollectorUriPath;
        this.token = token;
        // when size configuration setting is missing it's treated as "infinity",
        // i.e., any value is accepted.
        if (maxEventsBatchCount == 0 && maxEventsBatchSize > 0) {
            maxEventsBatchCount = Long.MAX_VALUE;
        } else if (maxEventsBatchSize == 0 && maxEventsBatchCount > 0) {
            maxEventsBatchSize = Long.MAX_VALUE;
        }
        this.maxEventsBatchCount = maxEventsBatchCount;
        this.maxEventsBatchSize = maxEventsBatchSize;
        this.retriesOnError = retriesOnError;
        this.metadata = metadata;
        if (sendModeStr != null) {
            if (sendModeStr.equals(SendModeSequential))
                this.sendMode = SendMode.Sequential;
            else if (sendModeStr.equals(SendModeSParallel))
                this.sendMode = SendMode.Parallel;
            else
                throw new IllegalArgumentException("Unknown send mode: " + sendModeStr);
        }

        if (delay > 0) {
            // start heartbeat timer
            timer = new Timer();
            timer.scheduleAtFixedRate(this, delay, delay);
        }
    }

    /**
     * Send a single logging event
     * @note in case of batching the event isn't sent immediately
     * @param severity event severity level (info, warning, etc.)
     * @param message event text
     * @throws ParseException 
     */
    public synchronized void send(final String severity, final String message) {
        LOGGER.info("Sending: "+message);
        // create event info container and add it to the batch
        HttpInputsEventInfo eventInfo =
                new HttpInputsEventInfo(severity, message);
        eventsBatch.add(eventInfo);
        eventsBatchSize += severity.length() + ((String) message).length();
        if (eventsBatch.size() >= maxEventsBatchCount || eventsBatchSize > maxEventsBatchSize) {
            flush();
        }
    }

    /**
     * Flush all pending events
     * @throws ParseException 
     */
    public synchronized void flush() {
        if (eventsBatch.size() > 0) {
            postEventsAsync(eventsBatch);
        }
        // Clear the batch. A new list should be created because events are
        // sending asynchronously and "previous" instance of eventsBatch object
        // is still in use.
        eventsBatch = new LinkedList<HttpInputsEventInfo>();
        eventsBatchSize = 0;
    }

    /**
     * Close events sender
     * @throws ParseException 
     */
    public void close() {
        if (timer != null)
            timer.cancel();
        flush();
    }

    /**
     * Timer heartbeat
     */
    @Override // TimerTask
    public void run() {
        flush();
    }

    /**
     * Disable https certificate validation of the splunk server.
     * This functionality is for development purpose only.
     */
    public void disableCertificateValidation() {
        disableCertificateValidation = true;
    }

    private String serializeEventInfo(HttpInputsEventInfo eventInfo) {
        // create event json content
        JSONObject event = new JSONObject();
        // event timestamp and metadata
        String index = metadata.get(MetadataIndexTag);
        String source = metadata.get(MetadataSourceTag);
        String sourceType = metadata.get(MetadataSourceTypeTag);
        event.put(MetadataTimeTag, String.format("%.3f", eventInfo.getTime()));
        if (index != null && index.length() > 0)
            event.put(MetadataIndexTag, index);
        if (source  != null && source.length() > 0)
            event.put(MetadataSourceTag, source);
        if (sourceType  != null && sourceType.length() > 0)
            event.put(MetadataSourceTypeTag, sourceType);
        // event body
        JSONObject body = new JSONObject();
        body.put("severity", eventInfo.getSeverity());
        body.put("message", stringOrJSON(eventInfo.getMessage()));
        //body.put("message", (eventInfo.getMessage()));

        // join event and body
        event.put("event", body);
        return event.toString();
    }

    private void startHttpClient() {
        if (httpClient != null) {
            // http client is already started
            return;
        }
        // limit max  number of async requests in sequential mode, 0 means "use
        // default limit"
        int maxConnTotal = sendMode == SendMode.Sequential ? 1 : 0;
        if (! disableCertificateValidation) {
            // create an http client that validates certificates
            httpClient = HttpAsyncClients.custom()
                    .setMaxConnTotal(maxConnTotal)
                    .build();
        } else {
            // create strategy that accepts all certificates
            TrustStrategy acceptingTrustStrategy = new TrustStrategy() {
                public boolean isTrusted(X509Certificate[] certificate,
                                         String type) {
                    return true;
                }
            };
            SSLContext sslContext = null;
            try {
                sslContext = SSLContexts.custom().loadTrustMaterial(
                        null, acceptingTrustStrategy).build();
                httpClient = HttpAsyncClients.custom()
                        .setMaxConnTotal(maxConnTotal)
                        .setHostnameVerifier(SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)
                        .setSSLContext(sslContext)
                        .build();
            } catch (Exception e) { }
        }
        httpClient.start();
    }

    // Currently we never close http client. This method is added for symmetry
    // with startHttpClient.
    private void stopHttpClient() throws SecurityException {
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (IOException e) {}
            httpClient = null;
        }
    }

    private void postEventsAsync(final List<HttpInputsEventInfo> eventsBatch) {
        startHttpClient();
        final String encoding = "utf-8";
        // convert events list into a string
        StringBuilder eventsBatchString = new StringBuilder();
        for (HttpInputsEventInfo eventInfo : eventsBatch)
            eventsBatchString.append(serializeEventInfo(eventInfo));
        // create http request
        final HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader(
                AuthorizationHeaderTag,
                String.format(AuthorizationHeaderScheme, token));
        StringEntity entity = new StringEntity(eventsBatchString.toString(), encoding);
        entity.setContentType(HttpContentType);
        httpPost.setEntity(entity);
        // post request
        httpClient.execute(httpPost, new FutureCallback<HttpResponse>() {
            long retriesCount = 0;

            public void completed(final HttpResponse response) {
                if (response.getStatusLine().getStatusCode() != 200) {
                    String reply = "";
                    try {
                        reply = EntityUtils.toString(response.getEntity(), encoding);
                    } catch (IOException e) {
                        reply = e.getMessage();
                    }
                   
                }
            }

            public void failed(final Exception ex) {
                if (retriesCount >= retriesOnError) {
                	System.out.println(ex.getMessage());
                } else {
                    // retry
                    retriesCount ++;
                    httpClient.execute(httpPost, this);
                }
            }

            public void cancelled() {}
        });
    }
    
	private Object stringOrJSON(String message) {
		try {
			return ((JSONObject) new JSONParser().parse(message));

		} catch (ParseException ex) {
			if (message instanceof String) {
				return (String)message;

			}
		}
		return (String)message;
	}
}
