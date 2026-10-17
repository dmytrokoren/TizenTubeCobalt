// Copyright 2026 Reis Can (reisxd). All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package dev.cobalt.dial;

import android.app.ActivityManager;
import android.content.Context;
import android.provider.Settings;
import dev.cobalt.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

public class DIALServer {
  private static final String TAG = "DIALServer";
  private static final int DIAL_PORT = 8012;
  private static final String DIAL_PATH = "/apps/youtube";
  private static final String DIAL_XML_PATH = "/dd.xml"; 
  private static DIALServer sInstance;
  private ServerSocket mServerSocket;
  private Thread mServerThread;
  private Context mContext;
  private volatile boolean mIsRunning = false;
  private volatile String mLastAdditionalData = "";

  private DIALServer() {}

  public static synchronized DIALServer getInstance() {
    if (sInstance == null) {
      sInstance = new DIALServer();
    }
    return sInstance;
  }

  /**
   * Starts the HTTP DIAL server.
   */
  public synchronized void start(Context context) throws IOException {
    if (mIsRunning) {
      return;
    }

    mContext = context;

    try {
      mServerSocket = new ServerSocket(DIAL_PORT);
      mIsRunning = true;
        mServerThread = new Thread(new ServerRunnable());
        mServerThread.setName("DIALServerThread");
        mServerThread.start();
    } catch (IOException e) {
        mIsRunning = false;
      throw e;
    }
  }

  /**
   * Stops the HTTP DIAL server.
   */
  public synchronized void stop() {
    mIsRunning = false;
    if (mServerSocket != null) {
      try {
        mServerSocket.close();
      } catch (IOException e) {
      }
    }
    if (mServerThread != null) {
      try {
        mServerThread.join(2000);
      } catch (InterruptedException e) {
        Log.w(TAG, "Interrupted waiting for server thread: " + e.getMessage());
      }
    }
  }

  /**
   * Checks if the server is currently running.
   */
  public synchronized boolean isRunning() {
    return mIsRunning;
  }

  /**
   * Generates the XML device descriptor response with the specified state and additionalData.
   * @param additionalData XML string to include in additionalData element (can be null/empty)
   * @param state the current state (running/paused/stopped)
   */
  private String generateDeviceDescriptor(String additionalData, String state) {
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<service xmlns=\"urn:dial-multiscreen-org:schemas:dial\" dialVer=\"1.7\">\n");
    xml.append("  <name>YouTube</name>\n");
    xml.append("  <options allowStop=\"true\"/>\n");
    xml.append("  <state>").append(state != null ? state : "stopped").append("</state>\n");
    xml.append("  <additionalData>");
    
    if (additionalData != null && !additionalData.isEmpty()) {
      xml.append(additionalData);
    } else {
      xml.append(" ");
    }
    
    xml.append("</additionalData>\n");
    xml.append("</service>\n");
    return xml.toString();
  }

  /**
   * Generates the dd.xml device descriptor used by discovery clients.
   */
  private String generateDdXml() {
    String friendlyName = getFriendlyName(mContext);
    String udn = getDialUdn(mContext);
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:r=\"urn:restful-tv-org:schemas:upnp-dd\">\n"
        + "<specVersion>\n"
        + "<major>1</major>\n"
        + "<minor>0</minor>\n"
        + "</specVersion>\n"
        + "<device>\n"
        + "<deviceType>urn:schemas-upnp-org:device:tvdevice:1</deviceType>\n"
        + "<friendlyName>" + escapeXml(friendlyName) + "</friendlyName>\n"
        + "<manufacturer/>\n"
        + "<modelName/>\n"
        + "<UDN>uuid:" + udn + "</UDN>\n"
        + "</device>\n"
        + "</root>\n";
  }

  public static String getFriendlyName(Context context) {
    String deviceName = null;
    if (context != null) {
      deviceName = Settings.Global.getString(context.getContentResolver(), "device_name");
    }
    if (deviceName == null || deviceName.isEmpty()) {
      return "YouTube";
    }
    return "YouTube (" + deviceName + ")";
  }

  public static String getDialUdn(Context context) {
    return generateDialUuid(getPlatformUuidSeed(context));
  }

  private static String getPlatformUuidSeed(Context context) {
    if (context == null) {
      return "unknown";
    }
    String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    if (androidId != null && !androidId.isEmpty()) {
      return androidId;
    }
    String deviceName = Settings.Global.getString(context.getContentResolver(), "device_name");
    if (deviceName != null && !deviceName.isEmpty()) {
      return deviceName;
    }
    return context.getPackageName();
  }

  public static String generateDialUuid(String platformUuid) {
    final String secret = "v=8FpigqfcvlM";
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      digest.update(secret.getBytes(StandardCharsets.UTF_8));
      digest.update(platformUuid.getBytes(StandardCharsets.UTF_8));
      byte[] mdValue = digest.digest();

      for (int i = 0; i < mdValue.length / 2; ++i) {
        mdValue[i] ^= mdValue[i + mdValue.length / 2];
      }

      return String.format(
          "%02x%02x%02x%02x-%02x%02x-%02x%02x%02x%02x",
          mdValue[0] & 0xff,
          mdValue[1] & 0xff,
          mdValue[2] & 0xff,
          mdValue[3] & 0xff,
          mdValue[4] & 0xff,
          mdValue[5] & 0xff,
          mdValue[6] & 0xff,
          mdValue[7] & 0xff,
          mdValue[8] & 0xff,
          mdValue[9] & 0xff);
    } catch (Exception e) {
      Log.w(TAG, "Failed to generate DIAL UUID: " + e.getMessage());
      return "549ad5e1-6dab-e15095a9";
    }
  }

  /**
   * Determines the current state of the YouTube app.
   * Returns "running" if app is in foreground, "paused" if in background, "stopped" if not running.
   */
  private String getYouTubeAppState() {
    if (mContext == null) {
      return "stopped";
    }
    try {
      String appPackageName = mContext.getPackageName();
      if (appPackageName == null || appPackageName.isEmpty()) {
        return "stopped";
      }

      ActivityManager activityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
      if (activityManager == null) {
        return "stopped";
      }
      // Check running tasks to see if YouTube is in foreground
      java.util.List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(1);
      if (!tasks.isEmpty()) {
        ActivityManager.RunningTaskInfo topTask = tasks.get(0);
        if (topTask.topActivity != null) {
          String topPkgName = topTask.topActivity.getPackageName();
          // Check if the current app is in foreground.
          if (appPackageName.equals(topPkgName)) {
            return "running";
          }
        }
      }
      // Check if the current app is running in background.
      java.util.List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
      if (processes != null) {
        for (ActivityManager.RunningAppProcessInfo proc : processes) {
          if (proc.pkgList != null) {
            for (String pkg : proc.pkgList) {
              if (appPackageName.equals(pkg)) {
                return "paused";
              }
            }
          }
        }
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to determine YouTube app state: " + e.getMessage());
    }
    return "stopped";
  }

  /**
   * Converts all form parameters into XML format for use as additionalData.
   * Each parameter becomes an XML element: <paramName>paramValue</paramName>
   */
  private String buildAdditionalDataXml(java.util.Map<String, String> params) {
    StringBuilder xml = new StringBuilder();
    for (java.util.Map.Entry<String, String> entry : params.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      // Replace non-XML-safe characters in tag names (e.g., hyphens with underscores)
      String tagName = key.replaceAll("[^a-zA-Z0-9_]", "_");
      xml.append("<").append(tagName).append(">");
      xml.append(escapeXml(value));
      xml.append("</").append(tagName).append(">\n");
    }
    return xml.toString();
  }

  /**
   * Escapes XML special characters.
   */
  private static String escapeXml(String input) {
    return input.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  /**
   * Checks if the request is from the local device.
   */
  private static boolean isLocalRequest(Socket clientSocket) {
    String remoteAddress = clientSocket.getInetAddress().getHostAddress();
    android.util.Log.v(TAG, "Checking if request is local - remoteAddress=" + remoteAddress);
    // Check if the client is from localhost or loopback
    if ("127.0.0.1".equals(remoteAddress) || "::1".equals(remoteAddress)) {
      return true;
    }
    // Check for private IP address ranges
    if (remoteAddress.startsWith("192.168.") || remoteAddress.startsWith("10.") ||
        remoteAddress.startsWith("172.")) {
      return true;
    }
    return false;
  }

  /**
   * Parses HTTP request line and body.
   */
  private static class HttpRequest {
    String method;
    String rawRequestLine;
    String path;
    Map<String, String> params = new HashMap<>();
    int contentLength = 0;

    static HttpRequest parse(BufferedReader reader) throws IOException {
      HttpRequest request = new HttpRequest();
      String line = reader.readLine();
      if (line == null || line.isEmpty()) {
        return null;
      }

      // Preserve raw request line for debugging
      request.rawRequestLine = line;

      String[] parts = line.split(" ");
      if (parts.length < 2) {
        return null;
      }

      request.method = parts[0];

      // Normalize the path: handle absolute URLs in the request line, strip query
      // string, and remove trailing slash (unless path is just "/").
      String rawPath = parts[1];
      try {
        if (rawPath.startsWith("http://") || rawPath.startsWith("https://")) {
          int idx = rawPath.indexOf('/', rawPath.indexOf("://") + 3);
          rawPath = (idx != -1) ? rawPath.substring(idx) : "/";
        }
      } catch (Exception e) {
        Log.w(TAG, "Failed to normalize request URL: " + e.getMessage());
      }

      // Strip query string
      int qIdx = rawPath.indexOf('?');
      if (qIdx != -1) {
        rawPath = rawPath.substring(0, qIdx);
      }

      // Remove trailing slash for consistent matching
      if (rawPath.endsWith("/") && rawPath.length() > 1) {
        rawPath = rawPath.substring(0, rawPath.length() - 1);
      }

      request.path = rawPath;

      // Parse headers
      while ((line = reader.readLine()) != null && !line.isEmpty()) {
        if (line.toLowerCase().startsWith("content-length:")) {
          try {
            request.contentLength = Integer.parseInt(line.substring(15).trim());
          } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid content-length: " + line);
          }
        }
      }

      // Read body if present
      if (request.contentLength > 0) {
        char[] bodyChars = new char[request.contentLength];
        int bytesRead = reader.read(bodyChars);
        if (bytesRead > 0) {
          String body = new String(bodyChars, 0, bytesRead);
          request.parseFormData(body);
        }
      }

      return request;
    }

    private void parseFormData(String body) {
      if (body == null || body.isEmpty()) {
        return;
      }

      try {
        String[] pairs = body.split("&");
        for (String pair : pairs) {
          int idx = pair.indexOf("=");
          if (idx > 0) {
            String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name());
            String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name());
            params.put(key, value);
          }
        }
      } catch (Exception e) {
        Log.w(TAG, "Failed to parse form data: " + e.getMessage());
      }
    }
  }

  /**
   * Server runnable that handles incoming connections.
   */
  private class ServerRunnable implements Runnable {
    @Override
    public void run() {
      while (mIsRunning) {
        try {
          Socket clientSocket = mServerSocket.accept();
          if (!mIsRunning) {
            clientSocket.close();
            break;
          }

          // Handle connection in a separate thread
          new Thread(new ConnectionHandler(clientSocket)).start();
        } catch (IOException e) {
          if (mIsRunning) {
            Log.e(TAG, "Error accepting connection: " + e.getMessage());
          }
        }
      }
      Log.d(TAG, "Server thread exiting");
    }
  }

  /**
   * Handles individual client connections.
   */
  private class ConnectionHandler implements Runnable {
    private Socket mClientSocket;

    ConnectionHandler(Socket socket) {
      mClientSocket = socket;
    }

    @Override
    public void run() {
      try {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(mClientSocket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(mClientSocket.getOutputStream(), StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.parse(reader);
        if (request == null) {
          sendResponse(writer, 400, "Bad Request", "");
          return;
        }

        String clientIp = mClientSocket.getInetAddress().getHostAddress();
        Log.i(TAG, "Connection from " + clientIp + " - raw=" + request.rawRequestLine + " normalized=" + request.path);

        if (request.path.toLowerCase().startsWith("/apps") && !request.path.equalsIgnoreCase(DIAL_PATH)) {
          sendRedirect(writer, "/apps/YouTube");
        } else if (request.path.equalsIgnoreCase(DIAL_XML_PATH)) {
          if ("GET".equalsIgnoreCase(request.method)) {
            sendResponse(writer, 200, "OK", generateDdXml());
          } else {
            sendResponse(writer, 405, "Method Not Allowed", "");
          }
        } else if (request.path.equalsIgnoreCase(DIAL_PATH)) {
          if ("GET".equalsIgnoreCase(request.method)) {
            handleGetRequest(writer);
          } else if ("POST".equalsIgnoreCase(request.method)) {
            // POST only allowed on /apps/YouTube, not on /dial.xml
            if (request.path.equalsIgnoreCase(DIAL_PATH)) {
              handlePostRequest(writer, request);
            } else {
              sendResponse(writer, 405, "Method Not Allowed", "");
            }
          } else {
            sendResponse(writer, 405, "Method Not Allowed", "");
          }
        } else {
          String diag = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
              "<diagnostic>\n" +
              "  <message>Not Found</message>\n" +
              "  <clientIp>" + escapeXml(clientIp) + "</clientIp>\n" +
              "  <rawRequestLine>" + escapeXml(request.rawRequestLine) + "</rawRequestLine>\n" +
              "  <normalizedPath>" + escapeXml(request.path) + "</normalizedPath>\n" +
              "</diagnostic>\n";
          sendResponse(writer, 404, "Not Found", diag);
        }

        writer.close();
        reader.close();
      } catch (IOException e) {
        Log.e(TAG, "Error handling connection: " + e.getMessage());
      } finally {
        try {
          mClientSocket.close();
        } catch (IOException e) {
          Log.e(TAG, "Error closing client socket: " + e.getMessage());
        }
      }
    }

    private void handleGetRequest(BufferedWriter writer) throws IOException {
      String state = getYouTubeAppState();
      // Return the last additionalData from a POST request, if any
      String xml = generateDeviceDescriptor(mLastAdditionalData, state);
      sendResponse(writer, 200, "OK", xml);
      Log.d(TAG, "GET /apps/YouTube responded with device descriptor (state=" + state + ")");
    }

    private void handlePostRequest(BufferedWriter writer, HttpRequest request) throws IOException {
      // Check if request is from local network
      if (!isLocalRequest(mClientSocket)) {
        Log.w(TAG, "Rejecting request from non-local address: " +
            mClientSocket.getInetAddress().getHostAddress());
        sendResponse(writer, 403, "Forbidden", "");
        return;
      }

      // Check for yumi parameter (indicates YouTube origin)
      if (request.params.containsKey("yumi")) {
        // This is from YouTube itself - convert all params to XML format as additionalData
        Log.i(TAG, "Received YouTube launch request with yumi parameter");
        String additionalDataXml = buildAdditionalDataXml(request.params);
        mLastAdditionalData = additionalDataXml; // Store for future GET requests
        String state = getYouTubeAppState();
        String response = generateDeviceDescriptor(additionalDataXml, state);
        sendResponse(writer, 200, "OK", response);
      } else {
        // Generic client request - pass data to MainActivity via url_params
        Log.i(TAG, "Received client launch request");

        // Convert params to url_params format (key=value&key=value...)
        StringBuilder urlParams = new StringBuilder();
        for (Map.Entry<String, String> entry : request.params.entrySet()) {
          if (urlParams.length() > 0) {
            urlParams.append("&");
          }
          urlParams.append(entry.getKey()).append("=").append(entry.getValue());
        }

        // Log params for debugging - app can be launched externally if needed
        Log.i(TAG, "Client launch params available: " + urlParams);

        String state = getYouTubeAppState();
        String response = generateDeviceDescriptor(null, state);
        sendResponse(writer, 200, "OK", response);
      }
    }

    private void sendResponse(BufferedWriter writer, int statusCode, String statusMessage,
        String responseBody) throws IOException {
      byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
      String localIp = mClientSocket.getLocalAddress().getHostAddress();
      StringBuilder response = new StringBuilder();
      response.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusMessage).append("\r\n");
      response.append("Content-Type: application/xml; charset=utf-8\r\n");
      response.append("APPLICATION-URL: http://").append(localIp).append(":").append(DIAL_PORT)
          .append("/apps/\r\n");
      response.append("Access-Control-Allow-Origin: *\r\n");
      response.append("Content-Length: ").append(responseBytes.length).append("\r\n");
      response.append("Connection: close\r\n");
      response.append("\r\n");
      response.append(responseBody);

      writer.write(response.toString());
      writer.flush();
    }

    private void sendRedirect(BufferedWriter writer, String location) throws IOException {
      StringBuilder response = new StringBuilder();
      response.append("HTTP/1.1 302 Found\r\n");
      response.append("Location: ").append(location).append("\r\n");
      response.append("Content-Length: 0\r\n");
      response.append("Connection: close\r\n");
      response.append("\r\n");
      writer.write(response.toString());
      writer.flush();
    }
  }
}
