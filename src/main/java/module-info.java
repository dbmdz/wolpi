import org.jspecify.annotations.NullMarked;

// Declare all classes in this module as null-marked, i.e. all parameters, return types and field
// values are non-null by default unless annotated with @Nullable.
@NullMarked
open module wolpi {
    requires app.photofox.vipsffm;
    requires com.google.common;
    requires io.github.classgraph;
    requires java.net.http;
    requires micrometer.core;
    requires org.apache.commons.pool2;
    requires org.apache.tomcat.embed.core;
    requires org.graalvm.polyglot;
    requires org.jspecify;
    requires org.slf4j;
    requires org.yaml.snakeyaml;
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.webmvc;
    requires info.picocli;
    requires org.apache.commons.io;
    requires java.desktop; // only for test assertions
    requires spring.boot.web.server;
    requires spring.boot.tomcat;
    requires spring.boot.jackson;
    requires tools.jackson.databind;
    requires tools.jackson.dataformat.toml;

    exports dev.mdz.wolpi;
    exports dev.mdz.wolpi.config;
    exports dev.mdz.wolpi.controller;
    exports dev.mdz.wolpi.extension;
    exports dev.mdz.wolpi.extension.exceptions;
    exports dev.mdz.wolpi.extension.util;
    exports dev.mdz.wolpi.extension.model;
    exports dev.mdz.wolpi.extension.mapping;
    exports dev.mdz.wolpi.iiif;
    exports dev.mdz.wolpi.iiif.exceptions;
    exports dev.mdz.wolpi.iiif.model;
    exports dev.mdz.wolpi.iiif.util;
    exports dev.mdz.wolpi.image;
    exports dev.mdz.wolpi.model;
    exports dev.mdz.wolpi.validation;
    exports dev.mdz.wolpi.validation.model;
    exports dev.mdz.wolpi.exceptions;
}
