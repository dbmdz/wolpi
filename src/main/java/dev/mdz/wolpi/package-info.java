/// Wolpi IIIF Image Server - Core package containing the main application and module organization.
/// This package contains the primary Spring Boot application class and organizes the following
/// modules:
/// - [dev.mdz.wolpi.config] - Application configuration classes
/// - [dev.mdz.wolpi.controller] - HTTP controller implementing IIIF Image API endpoints
/// - [dev.mdz.wolpi.iiif] - IIIF info.json generation, compliance checks and request parsing
/// - [dev.mdz.wolpi.image] - Image loading and processing operations using libvips
/// - [dev.mdz.wolpi.extension] - Extension system for JavaScript and Python plugins
/// - [dev.mdz.wolpi.model] - Shared domain model
/// - [dev.mdz.wolpi.exceptions] - Custom exception types for error handling
package dev.mdz.wolpi;
