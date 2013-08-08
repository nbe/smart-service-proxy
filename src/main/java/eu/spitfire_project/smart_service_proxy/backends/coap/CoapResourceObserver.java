package eu.spitfire_project.smart_service_proxy.backends.coap;

import de.uniluebeck.itm.spitfire.nCoap.application.CoapClientApplication;
import de.uniluebeck.itm.spitfire.nCoap.message.CoapRequest;
import de.uniluebeck.itm.spitfire.nCoap.message.CoapResponse;
import de.uniluebeck.itm.spitfire.nCoap.message.header.Code;
import de.uniluebeck.itm.spitfire.nCoap.message.header.MsgType;
import de.uniluebeck.itm.spitfire.nCoap.message.options.OptionRegistry;
import eu.spitfire_project.smart_service_proxy.core.SelfDescription;
import org.apache.log4j.Logger;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.local.DefaultLocalClientChannelFactory;

import java.net.Inet6Address;
import java.net.URI;


/**
 * Created with IntelliJ IDEA.
 * User: olli
 * Date: 18.04.13
 * Time: 09:43
 * To change this template use File | Settings | File Templates.
 */
public class CoapResourceObserver extends CoapClientApplication{

    private static Logger log =  Logger.getLogger(CoapResourceObserver.class.getName());

    private CoapBackend coapBackend;
    private Inet6Address serviceToObserveHost;
    private String serviceToObservePath;

    public CoapResourceObserver(CoapBackend coapBackend , Inet6Address observableServiceHost,
                                String observableServicePath){

        this.coapBackend = coapBackend;
        this.serviceToObserveHost = observableServiceHost;
        this.serviceToObservePath = observableServicePath;

    }

    public void writeRequestToObserveResource(){
        String targetURI = "coap://[" + serviceToObserveHost.getHostAddress() + "]" + serviceToObservePath;
        log.debug("Send request to observe " + targetURI);
        try {
            CoapRequest coapRequest = new CoapRequest(MsgType.CON, Code.GET, new URI(targetURI), this);
            coapRequest.setObserveOptionRequest();

            writeCoapRequest(coapRequest);

        } catch (Exception e) {
            log.error("Error while sending request to " + targetURI, e);
        }
    }

    @Override
    public void receiveResponse(CoapResponse coapResponse) {
        log.info("Received response for " + serviceToObservePath + " at " + serviceToObserveHost);

        //Update latest vital sign
        //coapBackend.updateLatestVitalSign(serviceToObserveHost);

        Object object;
        try {
            if(coapResponse.getOption(OptionRegistry.OptionName.OBSERVE_RESPONSE).isEmpty()
                    || coapResponse.getCode().isErrorMessage()){

                object = new ObservingFailedMessage(serviceToObserveHost, serviceToObservePath);
            }
            else{
                URI httpMirrorURI = (CoapBackend.createHttpURIs(serviceToObserveHost, serviceToObservePath))[1];
                object = new SelfDescription(coapResponse, httpMirrorURI);
            }

            Channel internalChannel =  createChannelForInternalMessages();
            ChannelFuture future = internalChannel.write(object);
            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {
                    if(!channelFuture.isSuccess()){
                        log.debug("Finished with error: " + channelFuture.getCause());
                    }
                }
            });
            future.addListener(ChannelFutureListener.CLOSE);
         }
        catch (Exception e) {
           log.error("Error in update notification of observed service.", e);
           return;
        }

    }

    private Channel createChannelForInternalMessages() throws Exception {
        ChannelFactory channelFactory = new DefaultLocalClientChannelFactory();

        ChannelPipelineFactory pipelineFactory = new ResourceUpdateChannelPipelineFactory(coapBackend);

        return channelFactory.newChannel(pipelineFactory.getPipeline());
    }

    @Override
    public void receiveEmptyACK() {
        log.info("Received empty ACK for request from " + serviceToObservePath + " at " + serviceToObserveHost);
    }

    @Override
    public void handleRetransmissionTimout() {
        log.info("Giving up retransmitting request to " + serviceToObservePath + " at " + serviceToObserveHost);
        try {
            Channel internalChannel = createChannelForInternalMessages();
            ChannelFuture future =
                    internalChannel.write(new ObservingFailedMessage(serviceToObserveHost, serviceToObservePath));
            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {
                    if(!channelFuture.isSuccess()){
                        log.debug("Finished with error: " + channelFuture.getCause());
                    }
                }
            });
            future.addListener(ChannelFutureListener.CLOSE);

        } catch (Exception e) {
            log.error("Unexpected error: ", e);
        }

    }


}
