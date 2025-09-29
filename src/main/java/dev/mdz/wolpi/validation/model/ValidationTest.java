package dev.mdz.wolpi.validation.model;

import dev.mdz.wolpi.iiif.model.IIIFComplianceLevel;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.graalvm.polyglot.Value;

public record ValidationTest (String pythonImportString, String name, String category, Map<IIIFVersion, IIIFComplianceLevel> complianceLevel, List<IIIFVersion> versions, Set<String> extraNames) {
  public static ValidationTest fromPyClass(Value pyClass) {
    Map<IIIFVersion, IIIFComplianceLevel> complianceLevels = new HashMap<>();
    Value compliance = pyClass.getMember("compliance_level");
    if (compliance.hasHashEntries()) {
      // It's a dict, so version-specific compiance levels
      var it = compliance.getHashKeysIterator();
      while (it.hasIteratorNextElement()) {
        var versionEnum = it.getIteratorNextElement();
        int complianceLevelVal = compliance.getHashValue(versionEnum).getMember("value").asInt();
        var complianceLevel = Objects.requireNonNull(IIIFComplianceLevel.fromInt(complianceLevelVal));
        complianceLevels.put(parseIIIFVersion(versionEnum), complianceLevel);
      }
    } else {
      var complianceLevel = Objects.requireNonNull(IIIFComplianceLevel.fromInt(compliance.getMember("value").asInt()));
      complianceLevels.put(IIIFVersion.V2, complianceLevel);
      complianceLevels.put(IIIFVersion.V3, complianceLevel);
    }

    List<IIIFVersion> versions = new ArrayList<>();
    var versionsVal = pyClass.getMember("versions");
    for (int i=0; i < versionsVal.getArraySize(); i++) {
      versions.add(parseIIIFVersion(versionsVal.getArrayElement(i)));
    }

    Set<String> extraNames = new HashSet<>();
    Value extraNamesVal = pyClass.getMember("extra_names");
    if (extraNamesVal != null && extraNamesVal.hasArrayElements()) {
      for (int i=0; i < extraNamesVal.getArraySize(); i++) {
        extraNames.add(extraNamesVal.getArrayElement(i).asString());
      }
    } else if (extraNamesVal != null && extraNamesVal.isString()) {
      extraNames.add(extraNamesVal.asString());
    }

    String importString = "from %s import %s".formatted(
        pyClass.getMember("__module__").asString(),
        pyClass.getMember("__name__").asString()
    );

    return new ValidationTest(
        importString,
        pyClass.getMember("name").asString(),
        pyClass.getMember("category").getMember("value").asString(),
        complianceLevels,
        versions,
        extraNames
    );
  }

  private static IIIFVersion parseIIIFVersion(Value val) {
    var versionStr = val.getMember("value").asString();
    return switch(versionStr) {
      case "2.0" -> IIIFVersion.V2;
      case "3.0" -> IIIFVersion.V3;
      default -> throw new IllegalArgumentException("Unknown IIIF version: " + versionStr);
    };
  }

  public String toString() {
    return this.name;
  }
}
