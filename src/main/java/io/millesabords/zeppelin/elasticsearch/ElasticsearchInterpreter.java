package io.millesabords.zeppelin.elasticsearch;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang.StringUtils;
import org.apache.zeppelin.interpreter.Interpreter;
import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.interpreter.InterpreterPropertyBuilder;
import org.apache.zeppelin.interpreter.InterpreterResult;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.search.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Elasticsearch Interpreter for Zeppelin.
 *
 * @author Bruno Bonnin
 */
public class ElasticsearchInterpreter extends Interpreter {

    private static Logger logger = LoggerFactory.getLogger(ElasticsearchInterpreter.class);

    public static final String ELASTICSEARCH_HOST = "elasticsearch.host";
    public static final String ELASTICSEARCH_PORT = "elasticsearch.port";
    public static final String ELASTICSEARCH_CLUSTER_NAME = "elasticsearch.cluster.name";

    static {
        Interpreter.register(
                "els",
                "elasticsearch",
                ElasticsearchInterpreter.class.getName(),
                new InterpreterPropertyBuilder()
                        .add(ELASTICSEARCH_HOST, "localhost", "The host for Elasticsearch")
                        .add(ELASTICSEARCH_PORT, "9300", "The port for Elasticsearch")
                        .add(ELASTICSEARCH_CLUSTER_NAME, "elasticsearch", "The cluster name for Elasticsearch")
                        .build());
    }

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Client client;
    private String host = "localhost";
    private int port = 9300;
    private String clusterName = "elasticsearch";

    public ElasticsearchInterpreter(Properties property) {
        super(property);
        this.host = getProperty(ELASTICSEARCH_HOST);
        this.port = Integer.parseInt(getProperty(ELASTICSEARCH_PORT));
        this.clusterName = getProperty(ELASTICSEARCH_CLUSTER_NAME);
    }

    @Override
    public void open() {
        try {
            final Settings settings = Settings.settingsBuilder()
                    .put("cluster.name", clusterName).build();
            client = TransportClient.builder().settings(settings).build()
                    .addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(host), port));
        }
        catch (IOException e) {
            logger.error("Open connection with Elasticsearch", e);
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    @Override
    public InterpreterResult interpret(String cmd, InterpreterContext interpreterContext) {
        logger.info("Run Elasticsearch command '" + cmd + "'");
        // RULES for an elasticsearch command:
        // <Method> /<index>/<type>/ <option> <JSON>
        // Method = search, get, index, delete, count

        final String[] items = StringUtils.split(cmd.trim(), " ", 3);

        if (items.length < 2) {
            return new InterpreterResult(InterpreterResult.Code.ERROR, "Arguments missing");
        }

        final String method = items[0];
        final String url = items[1];
        final String data = items.length > 2 ? items[2].trim() : null;

        final String[] urlItems = StringUtils.split(url.trim(), "/");

        try {
            if ("get".equalsIgnoreCase(method)) {
                return processGet(urlItems);
            }
            else if ("count".equalsIgnoreCase(method)) {
                return processCount(urlItems);
            }
            else if ("search".equalsIgnoreCase(method)) {
                return processSearch(urlItems, data);
            }
            else if ("index".equalsIgnoreCase(method)) {
                return processIndex(urlItems, data);
            }
            else if ("delete".equalsIgnoreCase(method)) {
                return processDelete(urlItems);
            }
    
            return new InterpreterResult(InterpreterResult.Code.ERROR, "Unknown method");
        }
        catch (Exception e) {
            return new InterpreterResult(InterpreterResult.Code.ERROR, "Error : " + e.getMessage());
        }
    }

    @Override
    public void cancel(InterpreterContext interpreterContext) {
        // Nothing to do
    }

    @Override
    public FormType getFormType() {
        return FormType.SIMPLE;
    }

    @Override
    public int getProgress(InterpreterContext interpreterContext) {
        return 0;
    }

    @Override
    public List<String> completion(String s, int i) {
        if (i == 0) {
            return Arrays.asList("search", "get", "index", "delete", "count");
        }
        return null;
    }

    /**
     * Processes a "get" request.
     * 
     * @param urlItems Items of the URL
     * @return Result of the get request, it contains a JSON-formatted string
     */
    private InterpreterResult processGet(String[] urlItems) {

        if (urlItems.length != 3 || StringUtils.isEmpty(urlItems[0]) || StringUtils.isEmpty(urlItems[1]) || StringUtils.isEmpty(urlItems[2])) {
            return new InterpreterResult(InterpreterResult.Code.ERROR, "Bad URL (it should be /index/type/id)");
        }

        final GetResponse response = client
            .prepareGet(urlItems[0], urlItems[1], urlItems[2])
            .get();
        if (response.isExists()) {
            final String json = gson.toJson(response.getSource());

            return new InterpreterResult(
                    InterpreterResult.Code.SUCCESS,
                    InterpreterResult.Type.TEXT,
                    json);
        }
        
        return new InterpreterResult(InterpreterResult.Code.ERROR, "Document not found");
    }

    /**
     * Processes a "count" request.
     * 
     * @param urlItems Items of the URL
     * @return Result of the count request, it contains the total hits
     */
    private InterpreterResult processCount(String[] urlItems) {

        if (urlItems.length > 2) {
            return new InterpreterResult(InterpreterResult.Code.ERROR, "Bad URL (it should be /index1,index2,.../type1,type2,...)");
        }

        final SearchResponse response = searchData(urlItems, "0");

        return new InterpreterResult(
                InterpreterResult.Code.SUCCESS,
                InterpreterResult.Type.TEXT,
                "" + response.getHits().getTotalHits());
    }

    /**
     * Processes a "search" request.
     * 
     * @param urlItems Items of the URL
     * @param data May contains the limit and the JSON of the request
     * @return Result of the search request, it contains a tab-formatted string of the matching hits
     */
    private InterpreterResult processSearch(String[] urlItems, String data) {

        if (urlItems.length > 2) {
            return new InterpreterResult(InterpreterResult.Code.ERROR, "Bad URL (it should be /index1,index2,.../type1,type2,...)");
        }
        
        final SearchResponse response = searchData(urlItems, data);

        return new InterpreterResult(
                InterpreterResult.Code.SUCCESS,
                InterpreterResult.Type.TABLE,
                buildResponseMessage(response.getHits().getHits()));
    }

    /**
     * Processes a "index" request.
     * 
     * @param urlItems Items of the URL
     * @param data JSON to be indexed
     * @return Result of the index request, it contains the id of the document
     */
    private InterpreterResult processIndex(String[] urlItems, String data) {
        
        if (urlItems.length < 2 || urlItems.length > 3) {
            return new InterpreterResult(InterpreterResult.Code.ERROR, "Bad URL (it should be /index/type or /index/type/id)");
        }
        
        final IndexResponse response = client
                .prepareIndex(urlItems[0], urlItems[1], urlItems.length == 2 ? null : urlItems[2])
                .setSource(data)
                .get();

        return new InterpreterResult(
                InterpreterResult.Code.SUCCESS,
                InterpreterResult.Type.TEXT,
                response.getId());
    }

    /**
     * Processes a "delete" request.
     * 
     * @param urlItems Items of the URL
     * @return Result of the delete request, it contains the id of the deleted document
     */
    private InterpreterResult processDelete(String[] urlItems) {

        if (urlItems.length != 3 || StringUtils.isEmpty(urlItems[0]) || StringUtils.isEmpty(urlItems[1]) || StringUtils.isEmpty(urlItems[2])) {
            return new InterpreterResult(InterpreterResult.Code.ERROR, "Bad URL (it should be /index/type/id)");
        }

        final DeleteResponse response = client
                .prepareDelete(urlItems[0], urlItems[1], urlItems[2])
                .get();
        
        if (response.isFound()) {
            return new InterpreterResult(
                    InterpreterResult.Code.SUCCESS,
                    InterpreterResult.Type.TEXT,
                    response.getId());
        }
        
        return new InterpreterResult(InterpreterResult.Code.ERROR, "Document not found");
    }
    
    private SearchResponse searchData(String[] urlItems, String data) {

        final SearchRequestBuilder reqBuilder = new SearchRequestBuilder(client, SearchAction.INSTANCE);
        
        if (urlItems.length >= 1) {
            reqBuilder.setIndices(StringUtils.split(urlItems[0], ","));
        }
        if (urlItems.length > 1) {
            reqBuilder.setTypes(StringUtils.split(urlItems[1], ","));
        }

        if (!StringUtils.isEmpty(data)) {
            // If size is 1 : we have a limit
            // If size is 2 : we have a limit and a JSON
            final String[] splittedData = StringUtils.split(data, " ", 2);
            if (splittedData.length == 2) {
                final Map source = gson.fromJson(splittedData[1], Map.class);
                reqBuilder.setExtraSource(source);
            }
            reqBuilder.setSize(Integer.parseInt(splittedData[0]));
        }

        final SearchResponse response = reqBuilder.get();
        
        return response;
    }

    private String buildResponseMessage(SearchHit[] hits) {
        
        if (hits == null || hits.length == 0) {
            return "";
        }
        
        //First : get all the keys in order to build an ordered list of the values for each hit
        //
        final Set<String> keys = new TreeSet<>();
        for (SearchHit hit : hits) {
            for (String key : hit.getSource().keySet()) {
                keys.add(key);
            }
        }

        // Next : build the header of the table
        //
        final StringBuffer buffer = new StringBuffer();
        for (String key : keys) {
            buffer.append(key).append('\t');
        }
        buffer.replace(buffer.lastIndexOf("\t"), buffer.lastIndexOf("\t") + 1, "\n");

        // Finally : build the result by using the key set
        //
        for (SearchHit hit : hits) {
            for (String key : keys) {
                final Object val = hit.getSource().get(key);
                if (val != null) {
                    buffer.append(val);
                }
                buffer.append('\t');
            }
            buffer.replace(buffer.lastIndexOf("\t"), buffer.lastIndexOf("\t") + 1, "\n");
        }

        return buffer.toString();
    }
}
