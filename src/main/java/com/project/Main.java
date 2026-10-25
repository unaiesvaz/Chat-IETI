package com.project;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class Main extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(Main.class.getResource("/assets/chat.fxml"));
        Parent root = loader.load();

        stage.setTitle("Chat IETI");
        stage.setScene(new Scene(root));
        stage.setMinWidth(600);
        stage.setMinHeight(450);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}