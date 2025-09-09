package dev.mdz.wolpi.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.DependencyRules.dependOnUpperPackages;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.DependencyRules;
import com.tngtech.archunit.library.GeneralCodingRules;

@AnalyzeClasses(
    packages = "dev.mdz.wolpi",
    importOptions = {ImportOption.DoNotIncludeTests.class})
public class ArchitectureTest {

  @ArchTest
  static final ArchRule overallArchitecture =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("App")
          .definedBy("dev.mdz.wolpi")
          .layer("Controller")
          .definedBy("..controller..")
          .layer("Extension Management")
          .definedBy("..wolpi.extension..")
          .layer("IIIF")
          .definedBy("..iiif..")
          .layer("Image Processing")
          .definedBy("..wolpi.image..")
          .layer("Model")
          .definedBy("dev.mdz.wolpi.model")

          // Support components
          .layer("Config")
          .definedBy("dev.mdz.wolpi.config")
          .layer("Utility") // Maybe better in a separate rule?
          .definedBy("..util..")
          .layer("Exceptions")// Maybe better in a separate rule?
          .definedBy("..exceptions..")

          // Rules
          .whereLayer("Controller")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("IIIF")
          .mayOnlyBeAccessedByLayers("Controller", "Image Processing")
          .whereLayer("Extension Management")
          .mayOnlyBeAccessedByLayers("App", "Image Processing")
          .whereLayer("Image Processing")
          .mayOnlyBeAccessedByLayers("Controller")
          .whereLayer("Model")
          .mayOnlyBeAccessedByLayers(
              "Controller", "Extension Management", "Image Processing", "IIIF")

          // Support components should not access other components
          .whereLayer("Exceptions")
          .mayNotAccessAnyLayer()
          .whereLayer("Utility")
          .mayNotAccessAnyLayer()
          .whereLayer("Config")
          .mayNotAccessAnyLayer();


  @ArchTest
  static final ArchRule exceptionConventions =
      classes()
          .that()
          .areAssignableTo(Exception.class)
          .should()
          .haveSimpleNameEndingWith("Exception")
          .andShould()
          .resideInAPackage("..exceptions..");


  @ArchTest
  static final ArchRule noGraalVMLeaks =
      noClasses()
          .that()
          .resideOutsideOfPackages("..util..", "..model.extensions", "..extension..")
          .should()
          .accessClassesThat(resideInAPackage("..graalvm.."));

  // Some predefined rule examples from ArchUnit:

  @ArchTest
  static final ArchRule testsSamePackageAsImplementation =
      GeneralCodingRules.testClassesShouldResideInTheSamePackageAsImplementation();

  @ArchTest
  static final ArchRule dontUseStandardStreams = noClasses().should(ACCESS_STANDARD_STREAMS);

  @ArchTest
  static final ArchRule dontDependOnUpperPackages =
      DependencyRules.NO_CLASSES_SHOULD_DEPEND_UPPER_PACKAGES;

  @ArchTest
  static final ArchRule dontDependOnUpperPackages2 = noClasses().should(dependOnUpperPackages());
}
