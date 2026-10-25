package com.project;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class ChatController { 
    //Atributos de la vista grafica (fxml)
    @FXML private Label statusLabel;
    @FXML private ScrollPane conversationScroll;
    @FXML private VBox messagesBox;
    @FXML private HBox attachmentPreview;
    @FXML private TextArea messageInput;
    @FXML private Button attachButton;
    @FXML private Button stopButton;
    @FXML private Button sendButton;

    private final ChatService chatService = new ChatService(); //Objecto encargado de la comunicacion con la API
    private final List<ChatMessage> conversation = new ArrayList<>();

    private byte[] imageBytes;
    private String imageMimeType;
    private String imageName;
    private ChatService.ChatRequest activeRequest;
    private Label streamingAnswer;

    @FXML
    private void initialize() {
        attachButton.setOnAction(event -> attachImage());
        sendButton.setOnAction(event -> sendMessage());
        stopButton.setOnAction(event -> stopRequest());
    }

    private void attachImage() { //Abre el explorador de archivos para poder enviar una imagen
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Selecciona una imagen");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Imágenes", "*.png", "*.jpg", "*.jpeg", "*.webp"));

        Window owner = messageInput.getScene() == null ? null : messageInput.getScene().getWindow(); //Obtiene la ventana principal 
        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return;
        }

        try {
            imageBytes = Files.readAllBytes(file.toPath());
            imageMimeType = mimeType(file);
            imageName = file.getName();
            showImagePreview();
            statusLabel.setText("Imagen lista");
        } catch (IOException error) {
            clearImage();
            statusLabel.setText("No se pudo abrir la imagen");
        }
    }

    private String mimeType(File file) throws IOException { //Indica al servidor el tipo de la imagen
        String name = file.getName().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";

        String detected = Files.probeContentType(file.toPath());
        if (detected != null && detected.startsWith("image/")) return detected;
        throw new IOException("Formato de imagen no compatible");
    }

    private void showImagePreview() { //Para poder ver un preview de la imagen
        attachmentPreview.getChildren().clear();

        ImageView thumbnail = new ImageView(new Image(new ByteArrayInputStream(imageBytes)));
        thumbnail.setFitWidth(72);
        thumbnail.setFitHeight(56);
        thumbnail.setPreserveRatio(true);

        Label name = new Label(imageName);
        Button remove = new Button("Quitar");
        remove.setOnAction(event -> clearImage());

        attachmentPreview.getChildren().addAll(thumbnail, name, remove);
        attachmentPreview.setManaged(true);
        attachmentPreview.setVisible(true);
    }

    private void clearImage() { //Borrar la imagen 
        imageBytes = null;
        imageMimeType = null;
        imageName = null;
        attachmentPreview.getChildren().clear();
        attachmentPreview.setVisible(false);
        attachmentPreview.setManaged(false);
    }

    private void sendMessage() { //Recoge el texto del mensaje y si hay una imagen, lo guarda como un mensaje 
        if (activeRequest != null) return;

        String text = messageInput.getText().trim();
        if (imageBytes == null && text.isEmpty()) return;
        if (imageBytes != null && text.isEmpty()) text = "Describe this image.";

        try {
            chatService.validateConfiguration();
        } catch (IllegalStateException error) {
            statusLabel.setText("Falta configurar IETI");
            addTextMessage("assistant", error.getMessage());
            return;
        }

        ChatMessage userMessage = new ChatMessage(
                "user", text, imageBytes, imageMimeType);
        conversation.add(userMessage);
        addConversationMessage(userMessage);

        boolean imageRequest = userMessage.hasImage();
        messageInput.clear();
        clearImage();

        statusLabel.setText(imageRequest ? "Pensando..." : "Generando...");
        streamingAnswer = imageRequest ? null : new Label();
        if (streamingAnswer != null) {
            streamingAnswer.setWrapText(true);
            streamingAnswer.setMaxWidth(560);
            addMessageRow("assistant", streamingAnswer, null);
        }
        setBusy(true);

        try {
            activeRequest = chatService.createRequest(List.copyOf(conversation), !imageRequest);
        } catch (RuntimeException error) {
            showRequestError(error);
            return;
        }

        ChatService.ChatRequest request = activeRequest;
        if (!imageRequest) {
            request.messageProperty().addListener((observable, oldText, newText) -> {
                if (activeRequest == request && streamingAnswer != null) {
                    streamingAnswer.setText(newText);
                    scrollToBottom();
                }
            });
        }

        request.setOnSucceeded(event -> { //Cuando se envia con exito 
            if (activeRequest != request) return;

            String answer = request.getValue();
            if (imageRequest) {
                addTextMessage("assistant", answer);
            }
            conversation.add(new ChatMessage("assistant", answer, null, null));
            finishRequest("Listo");
        });

        request.setOnFailed(event -> { //En caso de que falle el mensaje 
            if (activeRequest == request) {
                showRequestError(request.getException());
            }
        });


        Thread worker = new Thread(request, "ieti-chat-request");
        worker.setDaemon(true);
        worker.start();
    }

    private void stopRequest() {//Cancela la petición 
        if (activeRequest == null) return;

        ChatService.ChatRequest request = activeRequest;
        activeRequest = null;
        request.stopRequest();

        if (streamingAnswer != null && !streamingAnswer.getText().isBlank()) {
            conversation.add(new ChatMessage("assistant", streamingAnswer.getText(), null, null));
        }

        finishRequest("Detenido");
    }

    private void showRequestError(Throwable error) {
        String message = error == null || error.getMessage() == null
                ? "Error al contactar con IETI."
                : error.getMessage();

        if (streamingAnswer != null) {
            String partial = streamingAnswer.getText();
            streamingAnswer.setText((partial == null || partial.isBlank() ? "" : partial + "\n\n")
                    + "Error: " + message);
        } else {
            addTextMessage("assistant", "Error: " + message);
        }

        finishRequest("Error");
    }

    private void finishRequest(String status) {
        activeRequest = null;
        streamingAnswer = null;
        setBusy(false);
        statusLabel.setText(status);
        scrollToBottom();
    }

    private void addConversationMessage(ChatMessage message) { //Para poder ver el mensaje en la conversacion
        Label text = new Label(message.getText());
        text.setWrapText(true);
        text.setMaxWidth(560);

        ImageView thumbnail = null;
        if (message.hasImage()) {
            thumbnail = new ImageView(new Image(new ByteArrayInputStream(message.getImageBytes())));
            thumbnail.setFitWidth(180);
            thumbnail.setFitHeight(140);
            thumbnail.setPreserveRatio(true);
        }
        addMessageRow(message.getRole(), text, thumbnail);
    }

    private void addTextMessage(String role, String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(560);
        addMessageRow(role, label, null);
    }

    private void addMessageRow(String role, Label text, ImageView image) {
        VBox content = new VBox(6);
        content.setMaxWidth(580);
        content.getChildren().add(text);
        if (image != null) content.getChildren().add(image);

        HBox row = new HBox(content);
        row.setMaxWidth(Double.MAX_VALUE);
        row.setAlignment("user".equals(role) ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        messagesBox.getChildren().add(row);
        scrollToBottom();
    }

    private void setBusy(boolean busy) {
        messageInput.setDisable(busy);
        attachButton.setDisable(busy);
        sendButton.setDisable(busy);
        stopButton.setDisable(!busy);
    }

    private void scrollToBottom() {
        javafx.application.Platform.runLater(() -> conversationScroll.setVvalue(1.0));
    }
}