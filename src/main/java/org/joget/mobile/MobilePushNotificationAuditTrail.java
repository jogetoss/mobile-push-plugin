package org.joget.mobile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.joget.apps.app.lib.UserNotificationAuditTrail;
import org.joget.apps.app.model.AuditTrail;
import org.joget.apps.app.service.AppPluginUtil;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.PluginThread;
import org.joget.workflow.model.WorkflowActivity;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.model.service.WorkflowUserManager;

public class MobilePushNotificationAuditTrail extends UserNotificationAuditTrail {
    
    private final static String MESSAGE_PATH = "message/mobilePushNotificationAuditTrail";
    
    @Override
    public String getName() {
        return "Mobile Push Notification";
    }

    @Override
    public String getVersion() {
        return "8.0.0";
    }

    @Override
    public String getDescription() {
        return AppPluginUtil.getMessage("org.joget.mobile.MobilePushNotificationAuditTrail.pluginDesc", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getLabel() {
        return AppPluginUtil.getMessage("org.joget.mobile.MobilePushNotificationAuditTrail.pluginLabel", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClass().getName(), "/properties/mobilePushNotificationAuditTrail.json", null, true, MESSAGE_PATH);
    }
    
    @Override
    public Object execute(Map props) {
        return super.execute(props);
    }
    
    /**
     * Overriden method to push mobile push notification
     * @param props
     * @param auditTrail
     * @param workflowManager
     * @param users
     * @param wfActivity 
     */
    @Override
    protected void sendEmail (final Map props, final AuditTrail auditTrail, final WorkflowManager workflowManager, final List<String> users, final WorkflowActivity wfActivity) {
        final String mobileServerUrl = (String) props.get("mobileServerUrl");
        
        if (mobileServerUrl != null && !mobileServerUrl.isEmpty()) {
            new PluginThread(new Runnable() {

                public void run() {
                    WorkflowUserManager workflowUserManager = (WorkflowUserManager) AppUtil.getApplicationContext().getBean("workflowUserManager");

                    String base = (String) props.get("base");
                    String title = (String) props.get("title");
                    String body = (String) props.get("body");
                    String url = (String) props.get("url");
                    String parameterName = (String) props.get("parameterName");
                    String passoverMethod = (String) props.get("passoverMethod");
                    String webServiceKey = (String) props.get("webServiceKey");
                    if (webServiceKey == null || webServiceKey.trim().isEmpty()) {
                        webServiceKey = MobilePushPlugin.DEFAULT_WEB_SERVICE_KEY;
                    }

                    String activityInstanceId = wfActivity.getId();
                    String link = getLink(base, url, passoverMethod, parameterName, activityInstanceId);
                    
                    try {
                        for (String username : users) {
                            workflowUserManager.setCurrentThreadUser(username);
                            WorkflowAssignment wfAssignment = null;

                            int count = 0;
                            do {
                                wfAssignment = workflowManager.getAssignment(activityInstanceId);

                                if (wfAssignment == null) {
                                    Thread.sleep(4000); //wait for assignment creation
                                }
                                count++;
                            } while (wfAssignment == null && count < 5); // try max 5 times

                            if (wfAssignment != null) {

                                LogUtil.info(MobilePushNotificationAuditTrail.class.getName(), "Sending push notification to " + username);
                                CloseableHttpClient client = HttpClients.createDefault();
                                try {
                                    String serverUrl = mobileServerUrl + "/web/json/app/jms/plugin/org.joget.mobile.MobilePushPlugin/service?_a=push";
                                    String domain = base + "/web/mobile";
                                    HttpPost httpPost = new HttpPost(serverUrl);

                                    List<NameValuePair> params = new ArrayList<NameValuePair>();
                                    params.add(new BasicNameValuePair("key", webServiceKey));
                                    params.add(new BasicNameValuePair("username", username));
                                    params.add(new BasicNameValuePair("domain", AppUtil.processHashVariable(domain, wfAssignment, null, null)));
                                    params.add(new BasicNameValuePair("title", AppUtil.processHashVariable(title, wfAssignment, null, null)));
                                    params.add(new BasicNameValuePair("body", AppUtil.processHashVariable(body, wfAssignment, null, null)));
                                    params.add(new BasicNameValuePair("url", link));
                                    httpPost.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));

                                    CloseableHttpResponse response = client.execute(httpPost);
                                    LogUtil.info(MobilePushNotificationAuditTrail.class.getName(), "Sending push notification completed for " + username + ": " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
                                } catch (Exception ex) {
                                    LogUtil.error(MobilePushNotificationAuditTrail.class.getName(), ex, "Error sending push notification");
                                } finally {
                                    client.close();
                                }
                            } else {
                                LogUtil.debug(MobilePushNotificationAuditTrail.class.getName(), "Failed to retrieve assignment for " + username);
                            }
                        }
                    } catch (Exception e) {
                        LogUtil.error(MobilePushNotificationAuditTrail.class.getName(), e, "Error executing plugin");
                    }
                }
            }).start();
            
        } else {
            LogUtil.info(this.getClassName(), "Mobile Server URL is not configured.");
        }
    }    
    
}
