package org.joget.mobile;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.AndroidNotification;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.joget.apps.app.dao.EnvironmentVariableDao;
import org.joget.apps.app.dao.FormDefinitionDao;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.EnvironmentVariable;
import org.joget.apps.app.model.FormDefinition;
import org.joget.apps.app.service.AppPluginUtil;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.Form;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.apps.form.service.FormService;
import org.joget.apps.form.service.FormUtil;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.UuidGenerator;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.plugin.base.PluginWebSupport;
import org.json.JSONException;
import org.json.JSONObject;

public class MobilePushPlugin extends DefaultApplicationPlugin implements PluginWebSupport {

    public static final String APP_ID = "jms";
    public static final String FORM_REGISTRATION_ID = "mobile_registration";
    public static final String FORM_REGISTRATION_TABLE = "mobile_registration";
    public static final String FORM_MESSAGE_ID = "mobile_message";
    public static final String FORM_MESSAGE_TABLE = "mobile_message";
    private final static String MESSAGE_PATH = "message/mobilePushPlugin";
    public static final String ENV_VARIABLE_WEB_SERVICE_KEY = "webServiceKey";
    public static final String DEFAULT_WEB_SERVICE_KEY = "J0g3tm0b1l3pu5h";
    public static final String ENV_VARIABLE_FCM_SERVICE_ACCOUNT_JSON = "fcmServiceAccountJson";
    public static final String ENV_VARIABLE_FCM_DATABASE_NAME = "fcmDatabaseName";
    
    @Override
    public String getName() {
        return "Mobile Push Plugin";
    }

    @Override
    public String getDescription() {
        return AppPluginUtil.getMessage("org.joget.mobile.MobilePushPlugin.pluginDesc", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getVersion() {
        return "8.0.0";
    }

    @Override
    public String getLabel() {
        return AppPluginUtil.getMessage("org.joget.mobile.MobilePushPlugin.pluginLabel", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClass().getName(), "/properties/mobilePushPlugin.json", null, true, MESSAGE_PATH);
    }

    /**
     * Stores a device in the registration database. This registration database is used when sending push notifications.
     * Primary key is based on domain, username and device ID.
     * @param domain
     * @param username
     * @param deviceId
     * @param registrationId
     * @return true if registration is successful
     */
    protected boolean registerDevice(String domain, String username, String deviceId, String registrationId, String deviceInfo) {
        boolean success  = false;
        FormService formService = (FormService) AppUtil.getApplicationContext().getBean("formService");
        FormDefinitionDao formDefinitionDao = (FormDefinitionDao) AppUtil.getApplicationContext().getBean("formDefinitionDao");
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");

        FormData formData = new FormData();
        String userId = generateUserId(domain, username, deviceId);
        formData.setPrimaryKeyValue(userId);
        formData.addRequestParameterValues("domain", new String[] {domain});
        formData.addRequestParameterValues("username", new String[] {username});
        formData.addRequestParameterValues("deviceId", new String[] {deviceId});
        formData.addRequestParameterValues("registrationId", new String[] {registrationId});
        formData.addRequestParameterValues("deviceInfo", new String[] {deviceInfo});

        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        FormDefinition formDef = formDefinitionDao.loadById(FORM_REGISTRATION_ID, appDef);
        if (formDef != null) {
            String formJson = formDef.getJson();
            if (formJson != null) {
                Form form = (Form) formService.loadFormFromJson(formJson, formData);
                appService.submitForm(form, formData, false);
                success = true;
            }
        }
        return success;
    }
    
    protected boolean unregisterDevice(String domain, String username, String deviceId) {
        boolean success  = false;

        String userId = generateUserId(domain, username, deviceId);

        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        FormDefinitionDao formDefinitionDao = (FormDefinitionDao) AppUtil.getApplicationContext().getBean("formDefinitionDao");
        FormDefinition formDef = formDefinitionDao.loadById(FORM_REGISTRATION_ID, appDef);
        if (formDef != null) {
            FormDataDao formDataDao = (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");
            formDataDao.delete(FORM_REGISTRATION_ID, FORM_REGISTRATION_TABLE, new String[] { userId });
            success = true;
        }
        return success;
    }    

    protected boolean unregisterDeviceRegistrations(List<String> registrationIds) {
        boolean success  = false;

        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        FormDefinitionDao formDefinitionDao = (FormDefinitionDao) AppUtil.getApplicationContext().getBean("formDefinitionDao");
        FormDefinition formDef = formDefinitionDao.loadById(FORM_REGISTRATION_ID, appDef);
        if (registrationIds != null && formDef != null) {
            FormDataDao formDataDao = (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");
            for (String registrationId: registrationIds) {
                FormRowSet rows = formDataDao.find(FORM_REGISTRATION_ID, FORM_REGISTRATION_TABLE, " WHERE " + FormUtil.PROPERTY_CUSTOM_PROPERTIES + ".registrationId = ?", new Object[]{registrationId}, "dateModified", Boolean.TRUE, 0, 100);
                if (rows != null && !rows.isEmpty()) {
                    formDataDao.delete(FORM_REGISTRATION_ID, FORM_REGISTRATION_TABLE, rows);
                    success = true;
                }
            }
        }
        return success;
    }    

    protected String generateUserId(String domain, String username, String deviceId) {
        String userId = domain + ":" + username + ":" + deviceId;
        return userId;
    }
    
    /**
     * Sends push notification message for a specified domain and username.
     * @param apiKey
     * @param domain
     * @param username
     * @param title
     * @param body
     * @param url
     * @param image
     * @param style
     * @return
     * @throws IOException 
     */
    protected int sendPushNotification(String serviceAccountJson, String databaseName, String domain, String username, String title, String body, String url, Object[] extraData) throws IOException, InterruptedException, ExecutionException {
        int success = 0;
        
        // handle HTTP or HTTPS for domain
        String domain2 = domain;
        if (domain != null) {
            if (domain.toLowerCase().startsWith("http://")) {
                domain2 = "https://" + domain.substring("http://".length());
            } else if (domain.toLowerCase().startsWith("https://")) {
                domain2 = "http://" + domain.substring("https://".length());
            }
        }
        
        // retrieve device registrations for domain and username
        List<String> registrationIds = new ArrayList<String>();
        List<String> iosRegistrationIds = new ArrayList<String>();
        FormDataDao formDataDao = (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");
        FormRowSet rows = formDataDao.find(FORM_REGISTRATION_ID, FORM_REGISTRATION_TABLE, " WHERE (" + FormUtil.PROPERTY_CUSTOM_PROPERTIES + ".domain = ? OR " + FormUtil.PROPERTY_CUSTOM_PROPERTIES + ".domain = ?) AND " + FormUtil.PROPERTY_CUSTOM_PROPERTIES + ".username = ?", new Object[]{domain, domain2, username}, "dateModified", Boolean.TRUE, 0, 100);
        if (rows != null && !rows.isEmpty()) {
            for (FormRow row: rows) {
                String regId = row.getProperty("registrationId");
                String deviceInfo = row.getProperty("deviceInfo");
                boolean ios = deviceInfo != null && deviceInfo.contains("iOS");
                if (!ios) {
                    registrationIds.add(regId);
                } else {
                    iosRegistrationIds.add(regId);
                }
            }
        }
        
        String userId = generateUserId(domain, username, "");
        if (!registrationIds.isEmpty()) {
            // send Android FCM messages
            for (String registrationId: registrationIds) {
                success += sendFcmMessage(serviceAccountJson, databaseName, userId, registrationId, title, body, url, extraData, false);
            }
        }
        if (!iosRegistrationIds.isEmpty()) {
            // send IOS FCM messages
            for (String registrationId: iosRegistrationIds) {
                success += sendFcmMessage(serviceAccountJson, databaseName, userId, registrationId, title, body, url, extraData, true);
            }
        }
        return success;
    }
    
    /**
     * Sends a message to the Firebase FCM server
     * @param apiKey
     * @param userId
     * @param registrationId
     * @param title
     * @param body
     * @param url
     * @param image
     * @param style
     * @param ios
     * @return number of successful messages sent
     * @throws IOException 
     */
    protected int sendFcmMessage(String serviceAccountJson, String databaseName, String userId, String registrationId, String title, String body, String url, Object[] extraData, boolean ios) throws IOException, InterruptedException, ExecutionException {
        int success = 0;

        // init firebase app
        try {
            FirebaseApp.getInstance(FirebaseApp.DEFAULT_APP_NAME);
        } catch(IllegalStateException e) {
            FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(new ByteArrayInputStream(serviceAccountJson.getBytes("UTF-8"))))
                .setDatabaseUrl("https://" + databaseName + ".firebaseio.com/")
                .build();        
            FirebaseApp.initializeApp(options);
        }

        // build message
        Message.Builder messageBuilder = Message.builder();
        messageBuilder
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .putData("url", url)
                .setToken(registrationId);
        if (extraData != null && extraData.length > 0) {
            for (Object o : extraData) {
                Map mapping = (HashMap)o;
                String key = mapping.get("key").toString();
                String value = mapping.get("value").toString();
                if (!key.isEmpty()) {
                    messageBuilder.putData(key, value);
                }
            }
        } 
        if (ios) {
            messageBuilder.setApnsConfig(ApnsConfig.builder()
                    .setAps(Aps.builder()
                            .setCategory(title)
                            .setAlert(body)
                            .setBadge(1)
                            .setSound("true")
                            .build())
                    .build());
        } else {
            // Android specific configuration
            messageBuilder.setAndroidConfig(AndroidConfig.builder()
                    .setNotification(AndroidNotification.builder()
                            .setClickAction("com.adobe.phonegap.push.background.MESSAGING_EVENT")
                            .build())
                    .build());
        }
        Message message = messageBuilder.build();
        
        // Send a message to the device corresponding to the provided
        // registration token.
        try {
            String response = FirebaseMessaging.getInstance().sendAsync(message).get();
            if ("messaging/registration-token-not-registered".equals(response)) {
                List<String> failedRegistrationIds = new ArrayList<String>();
                failedRegistrationIds.add(registrationId);
                unregisterDeviceRegistrations(failedRegistrationIds);
                LogUtil.info(MobilePushPlugin.class.getName(), "Unregistered failed device registrations: " + failedRegistrationIds.size());
        } else {
                success = 1;
        }
        } catch(Exception e) {
            if ("com.google.firebase.messaging.FirebaseMessagingException: Requested entity was not found.".equals(e.getMessage())) {
                List<String> failedRegistrationIds = new ArrayList<String>();
                failedRegistrationIds.add(registrationId);
                unregisterDeviceRegistrations(failedRegistrationIds);
                LogUtil.info(MobilePushPlugin.class.getName(), "Unregistered failed device registrations: " + failedRegistrationIds.size());                
            }
        }

        // log result
        if (success > 0) {
            FormRowSet rowSet = new FormRowSet();
            FormRow formRow = new FormRow();
            formRow.setId(UuidGenerator.getInstance().getUuid());
            formRow.setProperty("title", title);
            formRow.setProperty("body", body);
            if (url != null) {
                formRow.setProperty("url", url);
    }
            formRow.setProperty("userId", userId);
            formRow.setDateCreated(new Date());
            formRow.setDateModified(new Date());
            rowSet.add(formRow);

            FormDataDao formDataDao = (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");
            formDataDao.saveOrUpdate(FORM_MESSAGE_ID, FORM_MESSAGE_TABLE, rowSet);
        }
        
        return success;
    }
    
    @Override
    public Object execute(Map properties) {
        String serviceAccountJson = getPropertyString("serviceAccountJson");
        String databaseName = getPropertyString("databaseName");
        String userId = getPropertyString("userId");
        String to = getPropertyString("to");
        String title = getPropertyString("title");
        String body = getPropertyString("body");
        String url = getPropertyString("url");
        boolean ios = "true".equals(getPropertyString("ios"));
        Object[] extraData = null;
        if (properties.get("extraData") instanceof Object[]){
            extraData = (Object[]) properties.get("extraData");
        }        
        try {
            int success = sendFcmMessage(serviceAccountJson, databaseName, userId, to, title, body, url, extraData, ios);
            LogUtil.info(MobilePushPlugin.class.getName(), "Mobile push notification [" + title + "] sent to " + to + ": " + success + "/1");
        } catch (Exception ex) {
            LogUtil.warn(MobilePushPlugin.class.getName(), "Mobile push notification [" + title + "] error: " + ex.getMessage());
        }
        return null;
    }
    
    @Override
    public void webService(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // Only allowed to use with the Joget Mobile Server App
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        if (appDef == null || !APP_ID.equalsIgnoreCase(appDef.getAppId())) { 
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        String action = request.getParameter("_a");
        if ("register".equals(action)) {
            handleRegistration(request, response);
        } else if ("unregister".equals(action)) {
            handleUnregistration(request, response);
        } else if ("push".equals(action)) {
            handlePush(request, response, appDef);
        } else {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }

    protected void handleRegistration(HttpServletRequest request, HttpServletResponse response) throws RuntimeException, IOException {
        String domain = request.getParameter("domain");
        String username = request.getParameter("username");
        String deviceId = request.getParameter("deviceId");
        String registrationId = request.getParameter("registrationId");
        String deviceInfo = request.getParameter("deviceInfo");
        boolean success = registerDevice(domain, username, deviceId, registrationId, deviceInfo);
        LogUtil.info(MobilePushPlugin.class.getName(), "Mobile registration " + domain + " " + username + " " + deviceId + " " + deviceInfo + ": " + success);
        try {
            JSONObject result = new JSONObject();
            result.append("success", success);
            response.setHeader("Access-Control-Allow-Origin", request.getHeader("Origin"));
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Content-type", "application/json");
            result.write(response.getWriter());
        } catch (JSONException ex) {
            throw new RuntimeException(ex);
        }
    }
    
    protected void handleUnregistration(HttpServletRequest request, HttpServletResponse response) throws RuntimeException, IOException {
        String domain = request.getParameter("domain");
        String username = request.getParameter("username");
        String deviceId = request.getParameter("deviceId");
        boolean success = unregisterDevice(domain, username, deviceId);
        LogUtil.info(MobilePushPlugin.class.getName(), "Mobile unregistration " + domain + " " + username + " " + deviceId + ": " + success);
        try {
            JSONObject result = new JSONObject();
            result.append("success", success);
            response.setHeader("Access-Control-Allow-Origin", request.getHeader("Origin"));
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Content-type", "application/json");
            result.write(response.getWriter());
        } catch (JSONException ex) {
            throw new RuntimeException(ex);
        }
    }
    
    protected void handlePush(HttpServletRequest request, HttpServletResponse response, AppDefinition appDef) throws IOException, RuntimeException {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Only POST allowed");
        } else {
            String requestKey = request.getParameter("key");
            String webServiceKey = null;
            EnvironmentVariableDao environmentVariableDao = (EnvironmentVariableDao)AppUtil.getApplicationContext().getBean("environmentVariableDao");
            EnvironmentVariable webServiceKeyVar = environmentVariableDao.loadById(ENV_VARIABLE_WEB_SERVICE_KEY, appDef);
            if (webServiceKeyVar != null) {
                webServiceKey = webServiceKeyVar.getValue();
            }
            if (webServiceKey == null || webServiceKey.trim().isEmpty()) {
                webServiceKey = DEFAULT_WEB_SERVICE_KEY;
            }
            if (webServiceKey != null && webServiceKey.equals(requestKey)) {
                // get service account
                String serviceAccountJson = null;
                EnvironmentVariable serviceAccountVar = environmentVariableDao.loadById(ENV_VARIABLE_FCM_SERVICE_ACCOUNT_JSON, appDef);
                if (serviceAccountVar != null) {
                    serviceAccountJson = serviceAccountVar.getValue();
                }

                // get database name
                String databaseName = null;
                EnvironmentVariable databaseNameVar = environmentVariableDao.loadById(ENV_VARIABLE_FCM_DATABASE_NAME, appDef);
                if (databaseNameVar != null) {
                    databaseName = databaseNameVar.getValue();
                }
                if (serviceAccountJson != null && !serviceAccountJson.trim().isEmpty() && databaseName != null && !databaseName.trim().isEmpty()) {
                    String domain = request.getParameter("domain");
                    String username = request.getParameter("username");
                    String title = request.getParameter("title");
                    String body = request.getParameter("body");
                    String url = request.getParameter("url");
                    try {
                        int success = sendPushNotification(serviceAccountJson, databaseName, domain, username, title, body, url, null);
                        LogUtil.info(MobilePushPlugin.class.getName(), "Mobile push " + domain + " " + username + " " + title + ": " + success);
                        JSONObject result = new JSONObject();
                        result.put("success", success);
                        response.setHeader("Access-Control-Allow-Origin", request.getHeader("Origin"));
                        response.setHeader("Access-Control-Allow-Credentials", "true");
                        response.setHeader("Content-type", "application/json");
                        result.write(response.getWriter());
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                } else {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Google Firebase service account JSON and/or database name not configured");
                }
            } else {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            }
        }
    }
    
}
