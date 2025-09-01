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

  exports dev.mdz.iiif.wolpi;
  exports dev.mdz.iiif.wolpi.config;
  exports dev.mdz.iiif.wolpi.controller;
  exports dev.mdz.iiif.wolpi.extension;
  exports dev.mdz.iiif.wolpi.iiif;
  exports dev.mdz.iiif.wolpi.image;
  exports dev.mdz.iiif.wolpi.model.extensions;
  exports dev.mdz.iiif.wolpi.model.image;
  exports dev.mdz.iiif.wolpi.model.params;

  // Needed to allow proxying of RuntimeContext by Spring AOP for request-scoped bean
  opens dev.mdz.iiif.wolpi.extension;
}
