package eu.spitfire_project.smart_service_proxy.visualization;

import eu.spitfire_project.smart_service_proxy.TimeProvider.SimulatedTimeParameters;
import eu.spitfire_project.smart_service_proxy.TimeProvider.SimulatedTimeUpdater;
import eu.spitfire_project.smart_service_proxy.core.httpClient.HttpClient;
import eu.spitfire_project.smart_service_proxy.backends.coap.noderegistration.annotation.AutoAnnotation;
import eu.spitfire_project.smart_service_proxy.backends.coap.noderegistration.annotation.SensorData;
import org.apache.log4j.Logger;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.*;
import org.joda.time.DateTime;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Callable;

import static org.jboss.netty.handler.codec.http.HttpMethod.POST;

/**
 * Created with IntelliJ IDEA.
 * Written by: Cuong Truong
 * Date: 10.10.12
 * Time: 11:22
 * To change this template use File | Settings | File Templates.
 */
public class VisualizerClient extends HttpClient implements Callable<HttpResponse> {

    private static Logger log = Logger.getLogger(VisualizerClient.class.getName());
    private static final VisualizerClient INSTANCE = new VisualizerClient();

    private static InetAddress VISUALIZER_IP;
    private static int VISUALIZER_PORT;
    private static String VISUALIZER_PATH;
    static{
        try {
            VISUALIZER_IP = InetAddress.getByName("localhost");
            //VISUALIZER_IP = InetAddress.getByName("spitfire-visualizer.wuxi.cn");
            VISUALIZER_PORT = 10000;
            VISUALIZER_PATH = "/visualizer";
        } catch (UnknownHostException e) {
            log.error("This should never happen.", e);
            throw new RuntimeException(e);
        }
    }

    private SimulatedTimeUpdater stu= new SimulatedTimeUpdater();
    public static int simulatedTime = 360; //Minutes!!!
    private static int imageIndex = 24;

    private final Object responseMonitor = new Object();
    public HttpResponse httpResponse;

    private boolean pauseVisualization = true;



    private VisualizerClient(){
    }

    public static VisualizerClient getInstance(){
        return INSTANCE;
    }

    @Override
    public HttpResponse call() {

        try {
            stu.doit(simulatedTime);
            synchronized (responseMonitor){

                this.httpResponse = null;

                writeHttpRequest(new InetSocketAddress(VISUALIZER_IP, VISUALIZER_PORT), createHttpRequest());

                log.debug("Wait for response.");
                if(httpResponse == null){
                    responseMonitor.wait(5000);
                }

                if (httpResponse != null && httpResponse.getStatus().equals(HttpResponseStatus.OK)) {

                    log.debug("Response received.");
                    System.out.println("TIME: " + simulatedTime);

                    DateTime simulatedDate = SimulatedTimeParameters.startDate.plusMinutes(VisualizerClient.simulatedTime);

                    int days = simulatedDate.getDayOfMonth() - SimulatedTimeParameters.startDate.getDayOfMonth();

                    System.out.println("==========================================");
                    System.out.println(days);
                    System.out.println("==========================================");

                    //Dennis's code
                    if((simulatedTime % 1440) - 1200 == 0 && days == 6){
                        simulatedTime = 360;
                        imageIndex = 24;
                    } else if((simulatedTime % 1440) - 1200 == 0){
                        simulatedTime += 600;
                        imageIndex = 24;
                    } else{
                        simulatedTime += 15;
                        imageIndex += 1;
                    }
                    //End Dennis's code*/

                    /*/Cuong's code
                    if((simulatedTime % 1440) - 1200 == 0){
                        simulatedTime += 600;
                        imageIndex = 24;
                    } else{
                        simulatedTime += 15;
                        imageIndex += 1;
                    }
                    //End Cuong's code*/
                }

                return httpResponse;
            }
        } catch (Exception e) {
            log.error("***************** EXCEPTION! *****************", e);
            return null;
        }
    }

    private HttpRequest createHttpRequest() {
        String visualizerService = "http://" + VISUALIZER_IP + ":" + VISUALIZER_PORT + VISUALIZER_PATH;
        HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, POST, visualizerService);

        String liveAnno = AutoAnnotation.getInstance().getLiveAnno();
        String payload = String.valueOf(simulatedTime) + "|" + String.valueOf(imageIndex % 96) + "|" //+ "20" + "\n";
            + String.valueOf(SimulatedTimeParameters.actualTemperature) + "|" +liveAnno+ "\n";

        System.out.println("==========================================");
        System.out.println("Send date to visualizer : " + simulatedTime);
        System.out.println("==========================================");


        for (int i=0; i< AutoAnnotation.getInstance().sensors.len(); i++) {
            SensorData sd = (SensorData) AutoAnnotation.getInstance().sensors.get(i);
            //log.debug("Payload for sensor "+sd.macAddr);
            //String timeStamp = String.valueOf(sd.getLatestTS());
            String timeStamp = String.valueOf(simulatedTime - 15);
            String value = String.format(Locale.US, "%.4f", sd.getLatestVL());
            String sc = String.format(Locale.US, "%.4f", sd.liveSc);
            String status = "";
            if (sd.newlyAdded) {
                status = "newlyadded";
                sd.newlyAdded = false;
            } else
                status = "working";
            String entry = status + "|" + sd.ipv6Addr + "|" + sd.macAddr + "|" + sd.FOI + "|"
                            + timeStamp + "|" +value + "|" + sc;
            payload += entry + "\n";
        }

        if (!"".equals(payload)){
            payload = payload.substring(0, payload.length()-1);
        }

        log.debug("Payload of request to visualizer service: \n------ " + payload);

        ChannelBuffer payloadBuffer = ChannelBuffers.copiedBuffer(payload.getBytes(Charset.forName("UTF-8")));
        httpRequest.setContent(payloadBuffer);
        httpRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, payloadBuffer.readableBytes());
        return httpRequest;
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent me){
        if(!(me.getMessage() instanceof HttpResponse)){
            ctx.sendUpstream(me);
            return;
        }

        synchronized (responseMonitor){
            httpResponse = (HttpResponse) me.getMessage();
            responseMonitor.notify();
        }
    }
//        if (!readingChunks) {
//            HttpResponse response = (HttpResponse) e.getMessage();
//            System.out.println("STATUS: " + response.getStatus());
//            System.out.println("VERSION: " + response.getProtocolVersion());
//            System.out.println();
//
//            if (!response.getHeaderNames().isEmpty()) {
//                for (String name: response.getHeaderNames()) {
//                    for (String value: response.getHeaders(name)) {
//                        System.out.println("HEADER: " + name + " = " + value);
//                    }
//                    System.out.println();
//                }
//
//                if (response.isChunked()) {
//                    readingChunks = true;
//                    System.out.println("CHUNKED CONTENT {");
//                }
//                else {
//                    ChannelBuffer content = response.getContent();
//                    if (content.readable()) {
//                        System.out.println("CONTENT {");
//                        System.out.println(content.toString(CharsetUtil.UTF_8));
//                        System.out.println("} END OF CONTENT");
//                    }
//                }
//            }
//            else {
//                HttpChunk chunk = (HttpChunk) e.getMessage();
//                if (chunk.isLast()) {
//                    readingChunks = false;
//                    System.out.println("} END OF CHUNKED CONTENT");
//                }
//                else {
//                    System.out.print(chunk.getContent().toString(CharsetUtil.UTF_8));
//                    System.out.flush();
//                }
//            }
//        }
//        if(!(me.getMessage() instanceof HttpRequest)){
//            ctx.sendUpstream(me);
//            return;
//        }
//
//        //Workaround until find a way to send string from client
//        if (pauseVisualization) {
//            pauseVisualization = false;
//            //new SimulatedTimeScheduler().run();
//            //stu.doit(simTime);
//        }
//
//        //Send a Response
//        if (!pauseVisualization) {
//            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
//
//            String payload = String.valueOf(simTime) + "|" + String.valueOf(imgIndex) + "|"
//                        + String.valueOf(SimulatedTimeParameters.actualTemperature) + "\n";
//
//
//            for (int i=0; i<AutoAnnotation.getInstance().sensors.len(); i++) {
//                SensorData sd = (SensorData) AutoAnnotation.getInstance().sensors.get(i);
//                String timeStamp = String.valueOf(sd.getLatestTS());
//                String value = String.format("%.4f", sd.getLatestVL());
//                String entry = sd.senID+"|"+sd.ipv6Addr+"|"+sd.macAddr+"|"+sd.FOI+"|"+timeStamp+"|"+value;
//                payload += entry + "\n";
//            }
//            if (payload != "")
//                payload = payload.substring(0, payload.length()-1);
//            //log.debug(payload);
//            response.setContent(ChannelBuffers.copiedBuffer(payload.getBytes(Charset.forName("UTF-8"))));
//            ChannelFuture future = Channels.write(ctx.getChannel(), response);
//            future.addListener(ChannelFutureListener.CLOSE);
//        }

}
