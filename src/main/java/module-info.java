module omut.aichat {
    // JDK modules
    requires java.desktop;
    requires java.net.http;
    requires jdk.jsobject;

    // Third-party modules
    requires atlantafx.base;
    requires com.fasterxml.jackson.databind;
    requires com.github.librepdf.openpdf;
    requires langchain4j.core;
    requires langchain4j.ollama;
    requires org.commonmark;
    requires org.jsoup;
    requires org.slf4j;
    requires org.slf4j.simple;

    // JavaFX modules
    requires javafx.controls;
    requires javafx.web;

    exports omut.aichat;
    exports omut.aichat.chat;
    exports omut.aichat.config;
    exports omut.aichat.service;
    exports omut.aichat.ui;
    exports omut.aichat.utils;
}