import org.jspecify.annotations.NullMarked;

// Declare all classes in this module as null-marked, i.e. all parameters, return types and field
// values are non-null by default unless annotated with @Nullable.
@NullMarked
module wolpi {
  requires app.photofox.vipsffm;
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.dataformat.toml;
  requires com.google.common;
  requires java.net.http;
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
  exports dev.mdz.wolpi.image.exceptions;
  exports dev.mdz.wolpi.model;

  // Needed to allow proxying of RuntimeContext by Spring AOP for request-scoped bean
  opens dev.mdz.wolpi.extension;
  opens dev.mdz.wolpi.extension.util;
}
