package com.project;

import javafx.concurrent.Task;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

public final class ChatService { //Clase encargada de realizar peticiones a la IA
    private static final String BASE_URL = "https://agents.ieti.site/v1";
    private static final String DEFAULT_MODEL = "Qwen38";

    public void validateConfiguration() { //Combrueba que la Clave exista 
        if (readApiKey().isBlank()) {
            throw new IllegalStateException(
                    "No encuentro la clave IETI en " + keyFilePath() + " ni en IETI_API_KEY.");
        }
        if (model().isBlank()) {
            throw new IllegalStateException("El nombre del modelo IETI está vacío.");
        }
    }

    public ChatRequest createRequest(List<ChatMessage> messages, boolean stream) {
        return new ChatRequest(readApiKey(), buildRequest(messages, stream), stream);
    }

    private JSONObject buildRequest(List<ChatMessage> messages, boolean stream) { //Construye el JSON que espera la API
        JSONArray jsonMessages = new JSONArray();

        for (ChatMessage message : messages) {
            JSONObject jsonMessage = new JSONObject().put("role", message.getRole());

            if (message.hasImage()) {
                String image = Base64.getEncoder().encodeToString(message.getImageBytes());
                String dataUrl = "data:" + message.getImageMimeType() + ";base64," + image;

                JSONArray content = new JSONArray()
                        .put(new JSONObject()
                                .put("type", "text")
                                .put("text", message.getText()))
                        .put(new JSONObject()
                                .put("type", "image_url")
                                .put("image_url", new JSONObject().put("url", dataUrl)));

                jsonMessage.put("content", content);
            } else {
                jsonMessage.put("content", message.getText());
            }

            jsonMessages.put(jsonMessage);
        }

        return new JSONObject()
                .put("model", model())
                .put("stream", stream)
                .put("messages", jsonMessages);
    }

    private String readApiKey() { //Lee la API 
        String fromEnvironment = System.getenv("IETI_API_KEY"); //Para configurar la API Key 
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.trim();
        }

        try {
            Path path = keyFilePath();
            if (Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException ignored) {
            // La interfaz mostrará que no se pudo encontrar una clave.
        }
        return "";
    }

    private Path keyFilePath() { //Busca la API KEY en local
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path configFolder = xdg == null || xdg.isBlank()
                ? Path.of(System.getProperty("user.home"), ".config")
                : Path.of(xdg);
        return configFolder.resolve("ieti-agents").resolve("agents_server_key");
    }

    private String model() {
        String configured = System.getenv("IETI_MODEL");
        return configured == null || configured.isBlank() ? DEFAULT_MODEL : configured.trim();
    }

    public static final class ChatRequest extends Task<String> {
        private final String apiKey;
        private final JSONObject requestBody;
        private final boolean stream;
        private volatile HttpURLConnection connection;

        private ChatRequest(String apiKey, JSONObject requestBody, boolean stream) {
            this.apiKey = apiKey;
            this.requestBody = requestBody;
            this.stream = stream;
        }

        @Override
        protected String call() throws Exception {
            HttpURLConnection current = (HttpURLConnection) URI
                    .create(BASE_URL + "/chat/completions")
                    .toURL()
                    .openConnection();

            connection = current;
            current.setRequestMethod("POST");
            current.setConnectTimeout(20_000);
            current.setReadTimeout(0);
            current.setDoOutput(true);
            current.setRequestProperty("Authorization", "Bearer " + apiKey);
            current.setRequestProperty("Content-Type", "application/json");
            current.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");

            if (isCancelled()) {
                current.disconnect();
                return "";
            }

            byte[] requestBytes = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            current.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream output = current.getOutputStream()) {
                output.write(requestBytes);
            }

            int status = current.getResponseCode();
            InputStream response = status >= 200 && status < 300
                    ? current.getInputStream()
                    : current.getErrorStream();

            if (response == null) {
                throw new IOException("IETI respondió HTTP " + status + " sin contenido.");
            }

            try (InputStream body = response) {
                if (status < 200 || status >= 300) {
                    String errorBody = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                    throw new IOException("IETI respondió HTTP " + status + ": " + errorMessage(errorBody));
                }
                if (isCancelled()) {
                    return "";
                }
                if (stream) {
                    return readStream(body);
                }
                String responseText = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                return readCompleteResponse(responseText);
            } finally {
                current.disconnect();
            }
        }

        private String readStream(InputStream responseBody) throws IOException {
            StringBuilder answer = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody, StandardCharsets.UTF_8))) {
                String line;
                while (!isCancelled() && (line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }

                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }

                    JSONObject event = new JSONObject(data);
                    JSONObject error = event.optJSONObject("error");
                    if (error != null) {
                        throw new IOException(error.optString("message", "Error del servidor IETI."));
                    }

                    JSONArray choices = event.optJSONArray("choices");
                    JSONObject choice = choices == null ? null : choices.optJSONObject(0);
                    JSONObject delta = choice == null ? null : choice.optJSONObject("delta");
                    Object content = delta == null ? null : delta.opt("content");
                    if (content == null && delta != null) {
                        content = delta.opt("text");
                    }
                    if (content == null && choice != null) {
                        content = choice.opt("text");
                    }

                    String piece = textFrom(content);
                    if (!piece.isEmpty()) {
                        answer.append(piece);
                        updateMessage(answer.toString());
                    }
                }
            }

            if (isCancelled()) {
                return answer.toString();
            }
            if (answer.isEmpty()) {
                throw new IOException("IETI terminó el streaming sin devolver texto.");
            }
            return answer.toString();
        }

        private String readCompleteResponse(String responseBody) throws IOException { //Procesa la respuesta JSON completa
            JSONObject json = new JSONObject(responseBody);
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                throw new IOException(error.optString("message", "Error del servidor IETI."));
            }

            JSONArray choices = json.optJSONArray("choices");
            JSONObject choice = choices == null ? null : choices.optJSONObject(0);
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            String answer = message == null ? "" : textFrom(message.opt("content"));

            if (answer.isBlank()) {
                throw new IOException("IETI devolvió una respuesta sin texto.");
            }
            return answer;
        }

        private String textFrom(Object value) { //Obtiene texto de varios formatos posibles
            if (value instanceof String text) {
                return text;
            }
            if (value instanceof JSONArray parts) {
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part != null) {
                        text.append(part.optString("text", ""));
                    }
                }
                return text.toString();
            }
            if (value instanceof JSONObject object) {
                return object.optString("text", "");
            }
            return "";
        }

        private String errorMessage(String body) { //Extrae el mensaje de error
            try {
                JSONObject json = new JSONObject(body);
                JSONObject error = json.optJSONObject("error");
                if (error != null) {
                    return error.optString("message", body);
                }
            } catch (Exception ignored) {
                // Si no es JSON, se muestra el texto devuelto por el servidor.
            }
            return body;
        }

        public void stopRequest() {
            cancel(true);
            HttpURLConnection current = connection;
            if (current != null) {
                current.disconnect();
            }
        }
    }
}