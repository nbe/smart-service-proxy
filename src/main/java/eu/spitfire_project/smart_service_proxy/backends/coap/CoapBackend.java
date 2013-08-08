/**
 * Copyright (c) 2012, all partners of project SPITFIRE (http://www.spitfire-project.eu)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 *    disclaimer.
 *
 *  - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *    following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 *  - Neither the name of the University of Luebeck nor the names of its contributors may be used to endorse or promote
 *    products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package eu.spitfire_project.smart_service_proxy.backends.coap;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import de.uniluebeck.itm.spitfire.nCoap.communication.callback.ResponseCallback;
import de.uniluebeck.itm.spitfire.nCoap.communication.core.CoapClientDatagramChannelFactory;
import de.uniluebeck.itm.spitfire.nCoap.message.CoapRequest;
import de.uniluebeck.itm.spitfire.nCoap.message.CoapResponse;
import de.uniluebeck.itm.spitfire.nCoap.message.options.Option;
import de.uniluebeck.itm.spitfire.nCoap.message.options.OptionRegistry;
import de.uniluebeck.itm.spitfire.nCoap.message.options.UintOption;
import eu.spitfire_project.smart_service_proxy.backends.coap.noderegistration.CoapResourceDiscoverer;
import eu.spitfire_project.smart_service_proxy.backends.coap.noderegistration.annotation.AutoAnnotation;
import eu.spitfire_project.smart_service_proxy.core.Backend;
import eu.spitfire_project.smart_service_proxy.core.SelfDescription;
import eu.spitfire_project.smart_service_proxy.core.httpServer.EntityManager;
import eu.spitfire_project.smart_service_proxy.utils.HttpResponseFactory;
import eu.spitfire_project.smart_service_proxy.utils.TString;
import org.apache.log4j.Logger;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.DatagramChannel;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import sun.net.util.IPAddressUtil;

import java.net.*;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static de.uniluebeck.itm.spitfire.nCoap.message.options.OptionRegistry.MediaType;
import static de.uniluebeck.itm.spitfire.nCoap.message.options.OptionRegistry.MediaType.APP_LINK_FORMAT;
import static de.uniluebeck.itm.spitfire.nCoap.message.options.OptionRegistry.OptionName.CONTENT_TYPE;


/**
 * 
 * @author Oliver Kleine
 *
 */

public class CoapBackend extends Backend{

    public static final int NODES_COAP_PORT = 5683;
    private static Logger log = Logger.getLogger(CoapBackend.class.getName());
        
    private HashMultimap<Inet6Address, String> registeredServices = HashMultimap.create();
    private boolean enableVirtualHttp;
    private DatagramChannel clientChannel = CoapClientDatagramChannelFactory.getInstance().getChannel();

    private HashBasedTable<Inet6Address, String, CoapResourceObserver> coapResourceObservers
            = HashBasedTable.create();


    /**
     * Create a new instance of the CoAPBackend-Application with a listening Datagram Socket on port 5683.
     */
    public CoapBackend(String prefix, boolean enableVirtualHttp) throws Exception{
        super();
        this.enableVirtualHttp = enableVirtualHttp;
        this.prefix = prefix;

        new Thread(new Runnable(){
            @Override
            public void run() {
                while(true){
                    //This is a helper parameter for catching the exception
                    Inet6Address actualNodeAddress = null;

                    try {
                        Thread.sleep(10000);
                        log.info("Check if registered nodes are alive...");

                        for(Inet6Address nodeAddress : registeredServices.keySet()){
                            actualNodeAddress = nodeAddress;
                            CoapResourceDiscoverer discoverer = new CoapResourceDiscoverer(nodeAddress, false);

                            //wait up to 2 minutes for a new list of registeredServices from host
                            discoverer.getFuture().get(30, TimeUnit.SECONDS);

                            //this is only reached if the node sent a response within 2 minutes
                            log.debug("Node is still alive.");
                        }
                    } catch (InterruptedException e) {
                        log.error(e);
                    } catch (ExecutionException e) {
                        log.error(e);
                    } catch (TimeoutException e) {
                        log.info("Node " + actualNodeAddress + " did not respond! Host is dead!");
                        unregisterServices(actualNodeAddress);
                    }
                }
            }
        }).start();
    }

    private synchronized void unregisterServices(Inet6Address nodeAddress){
        try{
            for(Inet6Address address : registeredServices.keySet()){
                log.debug("Address: " + address);
            }
            Set<String> removedPaths = registeredServices.removeAll(nodeAddress);
            log.info("" + removedPaths.size() + " registeredServices removed for " + nodeAddress);

            for(String path : removedPaths){
                URI[] httpURIs = createHttpURIs(nodeAddress, path);
                EntityManager.getInstance().entityDeleted(httpURIs[0], this);
                EntityManager.getInstance().virtualEntityDeleted(httpURIs[1], this);
            }


            Map observers = coapResourceObservers.row(nodeAddress);
            observers.clear();
            log.info("Removed all observers for " + nodeAddress);

        } catch (URISyntaxException e) {
            log.error(e);
        }
    }

    @Override
    public void bind(){
        EntityManager.getInstance().registerBackend(this, prefix);
    }

//    public void updateLatestVitalSign(Inet6Address remoteAddress){
//        latestVitalSigns.put(remoteAddress, System.currentTimeMillis());
//    }

    @Override
    public void writeRequested(final ChannelHandlerContext ctx, final MessageEvent me) throws Exception{
        if(me.getMessage() instanceof ObservingFailedMessage){
            ObservingFailedMessage message = (ObservingFailedMessage) me.getMessage();
            log.info("Stop observing of " + message.getServiceHost().getHostAddress() + message.getServicePath());

            if(coapResourceObservers.remove(message.getServiceHost(), message.getServicePath()) != null){
                log.info("Succesfully removed from list of observed registeredServices.");
            }
            else{
                log.error("Could not find service in list of observed registeredServices!");
            }

            return;
        }

        super.writeRequested(ctx, me) ;
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent me){
        
        if(!(me.getMessage() instanceof HttpRequest)) {
            ctx.sendUpstream(me);
            return;
        }
        
        final HttpRequest httpRequest = (HttpRequest) me.getMessage();

//        String path = httpRequest.getUri();
//        int index = path.indexOf("?");
//        if(index != -1){
//            path = path.substring(0, index);
//        }

        try {
            //check whether there is an appropriate CoAP service registered
            final Inet6Address targetUriHostAddress =
                    (Inet6Address) InetAddress.getByName(httpRequest.getHeader("HOST"));
            final String targetUriPath = httpRequest.getUri();

            if(registeredServices.containsEntry(targetUriHostAddress, targetUriPath)){
                //create CoAP target URI
                final URI coapTargetURI = URI.create("coap://["
                                              + targetUriHostAddress.getHostAddress()
                                              + "]:" + NODES_COAP_PORT
                                              + "/" + targetUriPath);

                //create CoAP request
                CoapRequest coapRequest = Http2CoapConverter.convertHttpRequestToCoAPMessage(httpRequest, coapTargetURI);
                coapRequest.setResponseCallback(new ResponseCallback() {
                    @Override
                    public void receiveResponse(CoapResponse coapResponse) {

                        Object response;
                        log.debug("Received CoAP response from " + me.getRemoteAddress());

                        //Update latest vital sign
//                        updateLatestVitalSign((Inet6Address) ((InetSocketAddress) me.getRemoteAddress()).getAddress());

                        try{
                            if(!(coapResponse.getCode().isErrorMessage()) && coapResponse.getPayload().readableBytes() > 0){
                                response = new SelfDescription(coapResponse,
                                        createHttpURIs(targetUriHostAddress, targetUriPath)[1]);
                                log.debug("SelfDescription object created from response payload.");
                            }
                            else{
                                log.debug("CoAP response is error message or without payload.");
                                response = Http2CoapConverter.convertCoapToHttpResponse(coapResponse,
                                        httpRequest.getProtocolVersion());
                            }
                        }
                        catch(Exception e){
                            log.error("Exception while receiving response.", e);
                            response = new DefaultHttpResponse(httpRequest.getProtocolVersion(),
                                    HttpResponseStatus.INTERNAL_SERVER_ERROR);
                            ((HttpResponse) response)
                                    .setContent(ChannelBuffers.wrappedBuffer(coapResponse.getPayload()));
                        }

                        ChannelFuture future = Channels.write(ctx.getChannel(), response);
                        future.addListener(ChannelFutureListener.CLOSE);
                    }

                    @Override
                    public void receiveEmptyACK() {
                        log.info("Empty ACK received from " + coapTargetURI);
                    }

                    @Override
                    public boolean isObserver() {
                        return false;
                    }


                    @Override
                    public void handleRetransmissionTimout() {
                        log.info("Retransmission timed out for " + coapTargetURI);
                    }
                });

                //send CoapRequest
                InetSocketAddress remoteSocketAddress =
                        new InetSocketAddress(coapTargetURI.getHost(), coapTargetURI.getPort());

                ChannelFuture future = Channels.write(clientChannel, coapRequest, remoteSocketAddress);
                future.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        log.info("CoAP request sent to " + coapTargetURI);
                    }
                });

            }
            else{
                log.info("Service " + targetUriPath + " unknwon for host " + targetUriHostAddress + ".");
                Object response = HttpResponseFactory.createHttpResponse(httpRequest.getProtocolVersion(),
                        HttpResponseStatus.NOT_FOUND);

                ChannelFuture future = Channels.write(ctx.getChannel(), response);
                future.addListener(ChannelFutureListener.CLOSE);
            }
        }
        catch (UnknownHostException e) {
            log.error("Error.", e);
        }
        catch (MethodNotAllowedException e) {
            log.error("Error.",e);
        }
    }
    
    /**
     * Returns the {@link InetAddress}es of the already known sensornodes
     * @return the {@link InetAddress}es of the already known sensornodes
     */
    public Set<Inet6Address> getSensorNodes(){
        return registeredServices.keySet();
    }

    /**
     * Unregisters all registeredServices known for the given address (CoAP host, resp. sensornode)
     * @param serverAddress The IPv6 address of the server to unregister all registeredServices of
     */
    public void deleteServices(Inet6Address serverAddress){
        log.debug("Delete registeredServices for " + serverAddress + ".");
        registeredServices.removeAll(serverAddress);
        coapResourceObservers.row(serverAddress).clear();
        log.info("Succesfully removed services from " + serverAddress + " from list of observed registeredServices.");
    }

    @Override
    public Set<URI> getResources(){
        HashSet<URI> result = new HashSet<URI>(registeredServices.size());
        for(Inet6Address address : registeredServices.keySet()){
            for(String path : registeredServices.get(address)){
                result.add(URI.create("http://" + address.getHostAddress()
                                     + EntityManager.SSP_HTTP_SERVER_PORT
                                     + "/" + path));
            }
        }
        return result;
    }

    /**
     * This method is called to process coapResponses containing a .well-known/core resource. It registers all listed
     * registeredServices at the EntityManager and starts the observation of the "minimal" resources.
      *@param coapResponse
     * @param remoteAddress
     */
    public void processWellKnownCoreResource(CoapResponse coapResponse, Inet6Address remoteAddress){

        //Check if content is set at all
        if(coapResponse.getOption(CONTENT_TYPE).isEmpty()){
            log.error("The Coap response did not contain a content type option (expected: " + APP_LINK_FORMAT + ")");
            return;
        }

        //Check if the content Type of the response is set to Core Link Format
        Option contentTypeOption = coapResponse.getOption(CONTENT_TYPE).get(0);
        OptionRegistry.MediaType contentType = MediaType.getByNumber(((UintOption)contentTypeOption).getDecodedValue());
        if(contentType != APP_LINK_FORMAT){
            log.error("The Coap response did not contain content of type " + APP_LINK_FORMAT + " but " + contentType);
            return;
        }

        //Content type is ok, so proceed
        ChannelBuffer payloadBuffer = coapResponse.getPayload();

        log.debug("Process ./well-known/core resource " +
                "(size: " + payloadBuffer.readableBytes() +") from " + remoteAddress.getHostAddress());

        //register each link as new entity
        byte[] bytes = new byte[payloadBuffer.readableBytes()];
        payloadBuffer.readBytes(bytes);
        String payload = new String(bytes, Charset.forName("UTF-8"));
        String[] links = payload.split(",");

        for (String link : links){
            log.debug("Process service " + link);
            //Ensure a "/" at the beginning of the path
            String path = link.substring(link.indexOf("<") + 1, link.indexOf(">"));
            if (path.indexOf("/") > 0){
                path = "/" + path;
            }
            try {
                registeredServices.put(remoteAddress, path);

                URI[] httpURIs = createHttpURIs(remoteAddress, path);
                EntityManager.getInstance().entityCreated(httpURIs[0], this);
                EntityManager.getInstance().virtualEntityCreated(httpURIs[1], this);

                //try to start observing of minimal resources
                if(path.contains("/_minimal")){
                    if(!coapResourceObservers.contains(remoteAddress, path)){
                        log.info("Send observe request for service " + path + " at " + remoteAddress);
                        CoapResourceObserver resourceObserver = new CoapResourceObserver(this, remoteAddress, path);
                        resourceObserver.writeRequestToObserveResource();

                        coapResourceObservers.put(remoteAddress, path, resourceObserver);
                    }
                }

//                //Virtual HTTP Server for Sensor nodes
//                if(enableVirtualHttp){
//                    URI virtualHttpServerUri = new URI("http://[" + remoteIP + "]" + path + "#");
//                    log.debug("[CoapBackend] New virtual HTTP service address: " + virtualHttpServerUri);
//                    resources.put(virtualHttpServerUri, coapURI);
//                    entityManager.entityCreated(virtualHttpServerUri, this);
//                }

            }
            catch (URISyntaxException e) {
                log.fatal("[CoapBackend] Error while creating URI. This should never happen.", e);
            }
        }

    }


//    /**
//     * Returns the IPv6 prefix of the net the CoapBackend is responsible for (e.g. 2001:638:b157:1)
//     * @return the IPv6 prefix of the net the CoapBackend is responsible for (e.g. 2001:638:b157:1)
//     */
//    public String getIPv6Prefix(){
//        return prefix.substring(4);
//    }

    public static URI[] createHttpURIs(Inet6Address remoteAddress, String path) throws URISyntaxException {

        URI[] result = new URI[2];

        String remoteIP = remoteAddress.getHostAddress();
        if(remoteIP.indexOf("%") != -1){
            remoteIP = remoteIP.substring(0, remoteIP.indexOf("%"));
        }

        log.debug("Remote IP original: " + remoteIP);
        //remove leading zeros per block
        remoteIP = remoteIP.replaceAll(":0000", ":0");
        remoteIP = remoteIP.replaceAll(":000", ":0");
        remoteIP = remoteIP.replaceAll(":00", ":0");
        remoteIP = remoteIP.replaceAll("(:0)([ABCDEFabcdef123456789])", ":$2");
        log.debug("Remote IP shortened 1: " + remoteIP);

        //return shortened IP
        remoteIP = remoteIP.replaceAll("((?:(?:^|:)0\\b){2,}):?(?!\\S*\\b\\1:0\\b)(\\S*)", "::$2");
        log.debug("Remote IP shortened 2: " + remoteIP);

        result[0] = new URI("http://" + remoteIP.replace(":", "-")
                            + "." + EntityManager.DNS_WILDCARD_POSTFIX
                            + ":" + EntityManager.SSP_HTTP_SERVER_PORT
                            + path);

        result[1] = new URI("http://[" + remoteIP + "]" + path);
;
        return result;
    }

    public URI createCoapTargetURI(String remoteIP, String path) throws URISyntaxException {
        if(IPAddressUtil.isIPv6LiteralAddress(remoteIP)){
            remoteIP = "[" + remoteIP + "]";
        }
        return new URI("coap://" + remoteIP + ":" + NODES_COAP_PORT + path);
    }

    public URI createVirtualHttpServerURI(){
        return null;
    }
}