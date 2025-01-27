package dev.ole.fritzbox;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ole.fritzbox.exceptions.AuthenticationException;
import dev.ole.fritzbox.exceptions.FritzboxException;
import dev.ole.fritzbox.model.FritzBoxLanguage;
import dev.ole.fritzbox.model.FritzOS;
import dev.ole.fritzbox.model.InternetInfo;
import dev.ole.fritzbox.model.LogEntry;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.java.Log;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Log
@Getter
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class FritzBox {


    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(
            "[dd.MM.yyyy HH:mm]" +
                    "[dd.MM.yyyy H:mm]" +
                    "[dd.MM.yyyy H:m]"
    );

    private static final String DEFAULT_DOMAIN = "fritz.box";
    private static final String DEFAULT_ADDRESS = "192.168.178.1";

    private static final String FAKE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final String REST_LOGIN = "login_sid.lua";
    private static final String REST_DATA = "data.lua";

    @Getter(AccessLevel.NONE)
    HttpClient client;

    String fritzboxAddress;
    String username;
    String password;
    FritzBoxLanguage defaultLanguage;

    @NonFinal
    String sessionId;

    public FritzBox(String username, String password) {
        this(null, username, password, null);
    }

    public FritzBox(String address, String username, String password) {
        this(address, username, password, null);
    }

    public FritzBox(String address, String username, String password, FritzBoxLanguage language) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        if (address == null || address.isEmpty()) {
            fritzboxAddress = DEFAULT_ADDRESS;
        } else {
            fritzboxAddress = address;
        }

        if (username == null || username.isEmpty()) {
            throw new NullPointerException("Given username cant be null or empty!");
        }
        this.username = username;

        if (password == null || password.isEmpty()) {
            throw new NullPointerException("Given password cant be null or empty!");
        }
        this.password = password;
        this.defaultLanguage = Objects.requireNonNullElse(language, FritzBoxLanguage.GERMAN);
    }

    @SuppressWarnings("all")
    private URI create(String restEndpoint) {
        return URI.create("http://" + fritzboxAddress + "/" + restEndpoint);
    }

    public void login() {
        URI authUrl = create(REST_LOGIN);

        String challenge;
        try {
            HttpRequest request = HttpRequest.newBuilder(authUrl)
                    .GET()
                    .header("Accept", "*/*")
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String xmlResponse = response.body();
            challenge = xmlResponse.substring(
                    xmlResponse.indexOf("<Challenge>") + 11,
                    xmlResponse.indexOf("<Challenge>") + 19
            );
        } catch (Exception e) {
            throw new FritzboxException("Couldn't request challenge from fritz box!", e);
        }

        String digestChallengePassword;
        try {
            digestChallengePassword = FritzBoxMd5.getResponseDigest(challenge, password);
        } catch (NoSuchAlgorithmException e) {
            throw new FritzboxException("Couldn't create digest from fritz box!", e);
        }

        String localSessionId;
        try {
            String queryParameters =
                    "response=" + challenge + '-' + digestChallengePassword +
                            "&username=" + username;
            HttpRequest request = HttpRequest.newBuilder(authUrl)
                    .header("Accept", "*/*")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(queryParameters))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String xmlResponse = response.body();

            localSessionId = xmlResponse.substring(
                    xmlResponse.indexOf("<SID>") + 5,
                    xmlResponse.indexOf("<SID>") + 21
            );
        } catch (IOException | InterruptedException e) {
            throw new FritzboxException("Couldn't request new session id from fritz box!", e);
        }

        if (localSessionId.equals("0000000000000000")) {
            throw new AuthenticationException();
        }

        this.sessionId = localSessionId;
    }

    public HttpResponse<String> getDataPostResponse(Map<String, String> postParams) {
        try {

            StringBuilder paramBuilder = new StringBuilder();
            for (String key : postParams.keySet()) {
                paramBuilder.append("&");
                paramBuilder.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
                paramBuilder.append("=");
                String value = postParams.get(key);
                if (value.isEmpty()) {
                    continue;
                }
                paramBuilder.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
            paramBuilder.append("&sid=").append(sessionId);
            paramBuilder.append("&no_sidrenew=");
            paramBuilder.append("&useajax=1");

            String queryParameters = paramBuilder.toString();
            if (!queryParameters.isEmpty()) {
                queryParameters = queryParameters.substring(1);
            }
            HttpRequest request = HttpRequest.newBuilder(create(REST_DATA))
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "gzip, deflate")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(queryParameters))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Couldn't request data of fritzbox!");
            }

            return response;
        } catch (IOException | InterruptedException e) {
            throw new FritzboxException("Couldn't request from internet!", e);
        }
    }

    public List<InternetInfo> getInternetInfo() {
        return getInternetInfo(FritzBoxLanguage.GERMAN);
    }

    public List<InternetInfo> getInternetInfo(FritzBoxLanguage language) {
        if (language == null) {
            throw new NullPointerException("Given language can't be null!");
        }
        HttpResponse<String> response = getDataPostResponse(new HashMap<>() {{
            put("xhr", "1");
            put("page", "netMoni");
            put("xhrId", "all");
            put("lang", language.getValue());
        }});
        if (response.statusCode() != 200) {
            throw new RuntimeException("Couldn't request internet info of fritzbox!");
        }

        List<InternetInfo> internetInfoList = new ArrayList<>();
        JsonObject rootJson = (JsonObject) JsonParser.parseString(response.body());
        JsonObject dataObject = rootJson.get("data").getAsJsonObject();
        JsonArray connectionsArray = dataObject.get("connections").getAsJsonArray();
        for (JsonElement connectionElement : connectionsArray) {
            JsonObject connectionObject = (JsonObject) connectionElement;

            String providerName = connectionObject.get("provider").getAsString();
            int upstreamBits = connectionObject.get("upstream").getAsInt();
            int mediumUpstreamBits = connectionObject.get("medium_upstream").getAsInt();
            int downstreamBits = connectionObject.get("downstream").getAsInt();
            int mediumDownstreamBits = connectionObject.get("medium_downstream").getAsInt();
            boolean connected = connectionObject.get("connected").getAsBoolean();

            JsonObject ipv4Object = connectionObject.get("ipv4").getAsJsonObject();
            String ipv4 = ipv4Object.get("ip").getAsString();
            boolean ipv4Connected = ipv4Object.get("connected").getAsBoolean();


            JsonObject ipv6Object = connectionObject.get("ipv6").getAsJsonObject();
            String ipv6 = ipv6Object.get("ip").getAsString();
            boolean ipv6Connected = ipv6Object.get("connected").getAsBoolean();

            internetInfoList.add(new InternetInfo(
                    connected,
                    ipv4,
                    ipv4Connected,
                    ipv6,
                    ipv6Connected,
                    providerName,
                    upstreamBits,
                    mediumUpstreamBits,
                    downstreamBits,
                    mediumDownstreamBits
            ));
        }

        return internetInfoList;
    }

    public void reconnect() {
        reconnect(FritzBoxLanguage.GERMAN);
    }

    public void reconnect(FritzBoxLanguage language) {
        if (language == null) {
            throw new NullPointerException("Given language can't be null!");
        }
        getDataPostResponse(new HashMap<>() {{
            put("xhr", "1");
            put("page", "netMoni");
            put("xhrId", "reconnect");
            put("disconnect", "true");
            put("lang", language.getValue());
        }});

        getDataPostResponse(new HashMap<>() {{
            put("xhr", "1");
            put("page", "netMoni");
            put("xhrId", "reconnect");
            put("connect", "true");
            put("lang", language.getValue());
        }});
    }

    public FritzOS getFritzOS() {
        HttpResponse<String> response = getDataPostResponse(new HashMap<>() {{
            put("xhr", "1");
            put("page", "update");
        }});

        String htmlResponse = response.body();

        Document parse = Jsoup.parse(htmlResponse);

        Elements elements = parse.selectXpath("//div[@id='uiVersion']//div[contains(@class, 'fakeTextInput')]");

        String fritzOsVersion = elements.get(0).html();
        LocalDateTime dateOfLastUpdate = LocalDateTime.parse(elements.get(1).html(), DATE_FORMATTER);
        LocalDateTime dateOfLastAutomaticCheck = LocalDateTime.parse(elements.get(2).html(), DATE_FORMATTER);

        return new FritzOS(
                fritzOsVersion,
                dateOfLastUpdate,
                dateOfLastAutomaticCheck
        );
    }

    public List<LogEntry> getLogEvents(FritzBoxLanguage language) {
        if (language == null) {
            throw new NullPointerException("Given language can't be null!");
        }
        HttpResponse<String> response = getDataPostResponse(new HashMap<>() {{
            put("xhr", "1");
            put("lang", language.getValue());
            put("page", "log");
            put("xhrId", "all");
        }});

        JsonObject rootObject = new JsonObject();
        JsonObject dataObject = rootObject.get("data").getAsJsonObject();
        JsonArray logArray = dataObject.get("log").getAsJsonArray();

        List<LogEntry> logEntryList = new ArrayList<>();
        for (JsonElement logElement : logArray) {
            JsonObject logObject = (JsonObject) logElement;

            String helpLink = logObject.get("helplink").getAsString();
            String time = logObject.get("time").getAsString();
            String group = logObject.get("group").getAsString();
            int id = logObject.get("group").getAsInt();
            String message = logObject.get("message").getAsString();
            String date = logObject.get("date").getAsString();
            int noHelp = logObject.get("noHelp").getAsInt();

            logEntryList.add(new LogEntry(
                    helpLink,
                    time,
                    group,
                    id,
                    message,
                    date,
                    noHelp
            ));
        }

        return logEntryList;
    }
}