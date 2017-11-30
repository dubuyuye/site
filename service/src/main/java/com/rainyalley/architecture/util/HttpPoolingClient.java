package com.rainyalley.architecture.util;

import net.jcip.annotations.NotThreadSafe;
import org.apache.http.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.StandardHttpRequestRetryHandler;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


@NotThreadSafe
public class HttpPoolingClient extends CloseableHttpClient{

    /**
     * 将请求标记为可重试
     */
    private final static String http_req_retry = "http.request_retry";

	private Logger logger = LoggerFactory.getLogger(getClass());


    /**
     * 从连接池中获取连接的时间
     */
    private int connectionRequestTimeout = 5000;

    /**
     * 连接上服务器(握手成功)的时间
     */
    private int socketTimeout = 5000;

    /**
     * 服务器返回数据(response)的时间
     */
    private int connectTimeout = 5000;

    /**
     * 连接池最大连接数
     */
    private int maxTotal = 1000;

    /**
     * 每个路由最大连接数
     */
    private int maxPerRoute = 200;

    /**
     * 指定路由的最大连接数
     */
    private Map<String, Integer> routeMax = Collections.emptyMap();


    /**
     * 重试次数
     */
    private int retryTimes = 3;


    private Charset charset = Charset.forName("UTF-8");


    private  CloseableHttpClient client;

    private  RequestConfig requestConfig;

    private PoolingHttpClientConnectionManager connectionManager;

    private org.apache.http.client.HttpRequestRetryHandler retryHandler;

    public void init(){
        requestConfig = RequestConfig.custom()
                .setSocketTimeout(socketTimeout)
                .setConnectTimeout(connectTimeout)
                .setConnectionRequestTimeout(connectionRequestTimeout).build();

        connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(maxTotal);
        connectionManager.setDefaultMaxPerRoute(maxPerRoute);

        for (Map.Entry<String, Integer> entry : routeMax.entrySet()) {
            String[] hostAndPort = entry.getKey().split(":");
            HttpHost httpHost = new HttpHost(hostAndPort[0], Integer.valueOf(hostAndPort[1]));
            // 将目标主机的最大连接数增加
            connectionManager.setMaxPerRoute(new HttpRoute(httpHost), entry.getValue());
        }

        retryHandler = new SpecificHttpRequestRetryHandler(retryTimes, false);

        client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .setRetryHandler(retryHandler)
                .build();
    }


    @Override
    protected CloseableHttpResponse doExecute(HttpHost target, HttpRequest request, HttpContext context) throws IOException {
        return client.execute(target, request, context);
    }


    public String get(String url, Header... header) throws IOException, IllegalStateException{
        HttpGet httpGet = new HttpGet(url);
        for (Header h : header) {
            httpGet.addHeader(h);
        }

        try {
            if(logger.isDebugEnabled()){
                logger.debug("get send " + httpGet);
            }

            String result = doSend(httpGet, null);

            if(logger.isDebugEnabled()){
                logger.debug("get receive " + result);
            }

            return result;
        } finally {
            httpGet.releaseConnection();
        }

    }

    private String doSend(final HttpUriRequest request, final HttpContext context) throws IOException, IllegalStateException{
        CloseableHttpResponse response = null;
        try {
            response = this.execute(request, context);
            int respStatus = response.getStatusLine().getStatusCode();
            if (respStatus == HttpStatus.SC_OK) {
                HttpEntity httpEntity = response.getEntity();
                return httpEntity != null ? EntityUtils.toString(httpEntity, charset) : null;
            } else {
                throw new IllegalStateException();
            }
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private List<NameValuePair> transferMap(Map<String, String> paramMap) {
        List<NameValuePair> formParams = new ArrayList<NameValuePair>();

        for (Map.Entry<String, String> entry : paramMap.entrySet()) {
            formParams.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
        }
        return formParams;
    }


    public String post(String url, Map<String,String> params, Header... header) throws IOException, IllegalStateException{
        return post(url, params, false, header);
    }


    public String post(String url, Map<String,String> params, boolean retry, Header... header) throws IOException, IllegalStateException {
        HttpPost httpPost = new HttpPost(url);
        for (Header h : header) {
            httpPost.addHeader(h);
        }
        httpPost.addHeader(HttpHeaders.CONTENT_ENCODING, charset.name());

        UrlEncodedFormEntity entity = new UrlEncodedFormEntity(transferMap(params), Consts.UTF_8);
        httpPost.setEntity(entity);
        try {
            if(logger.isDebugEnabled()){
                logger.debug("post send " + httpPost);
            }

            BasicHttpContext context = new BasicHttpContext();
            if(retry){
                context.setAttribute(http_req_retry, true);
            }
            String result = doSend(httpPost, context);

            if(logger.isDebugEnabled()){
                logger.debug("post receive " + result);
            }

            return result;
        } finally {
            httpPost.releaseConnection();
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    @Override
    public HttpParams getParams() {
        return client.getParams();
    }

    @Override
    public ClientConnectionManager getConnectionManager() {
        throw new UnsupportedOperationException();
    }


    /**
     * 指定的请求将重试
     */
    private static class SpecificHttpRequestRetryHandler extends StandardHttpRequestRetryHandler{

        public SpecificHttpRequestRetryHandler(int retryCount, boolean requestSentRetryEnabled) {
            super(retryCount, requestSentRetryEnabled);
        }

        @Override
        public boolean retryRequest(IOException exception, int executionCount, HttpContext context) {
            boolean retry = super.retryRequest(exception, executionCount, context);

            //如果之前已判断为可重试，则重试
            if(retry){
                return true;
            }

            if (executionCount > this.getRetryCount()) {
                //超过次数则不重试
                return false;
            }

            //如果之前判断为不可重试，此判断是否指定为重试
            return Boolean.TRUE.equals(context.getAttribute(http_req_retry));

        }
    }
}