package org.apache.drill.common.util;

import static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.drill.common.config.CommonConstants;
import org.reflections.Reflections;
import org.reflections.adapters.JavassistAdapter;
import org.reflections.scanners.AbstractScanner;
import org.reflections.util.ConfigurationBuilder;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;
import javassist.bytecode.FieldInfo;

public final class FunctionResolver {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FunctionResolver.class);
  private static final String FUNCTION_REGISTRY_FILE = "META-INF/function-registry/registry.json";

  private static final ObjectMapper mapper = new ObjectMapper().enable(INDENT_OUTPUT);
  private static final ObjectReader reader = mapper.reader(ScanResult.class);
  private static final ObjectWriter writer = mapper.writerWithType(ScanResult.class);

  static final class FunctionScanner extends AbstractScanner {
    private String FUNCTION_ANN = "org.apache.drill.exec.expr.annotations.FunctionTemplate";
    public List<FunctionDescriptor> functions = new ArrayList<>();

    @Override
    public void scan(final Object cls) {
      try {
        ClassFile classFile = (ClassFile)cls;

        AnnotationsAttribute annotations = ((AnnotationsAttribute)classFile.getAttribute(AnnotationsAttribute.visibleTag));
        if (annotations != null) {
          boolean isFunctionTemplate = false;
          for (javassist.bytecode.annotation.Annotation a : annotations.getAnnotations()) {
            if (a.getTypeName().equals(FUNCTION_ANN)) {
              isFunctionTemplate = true;
            }
          }
          if (isFunctionTemplate) {
            List<AnnotationDescriptor> classAnnotations = getAnnotationDescriptors(annotations);
            @SuppressWarnings("unchecked")
            List<FieldInfo> classFields = classFile.getFields();
            List<FieldDescriptor> fieldDescriptors = new ArrayList<>(classFields.size());
            for (FieldInfo field : classFields) {
              String fieldName = field.getName();
              AnnotationsAttribute fieldAnnotations = ((AnnotationsAttribute)field.getAttribute(AnnotationsAttribute.visibleTag));
              fieldDescriptors.add(new FieldDescriptor(fieldName, field.getDescriptor(), getAnnotationDescriptors(fieldAnnotations)));
            }
            functions.add(new FunctionDescriptor(classFile.getName(), classAnnotations, fieldDescriptors));
          }
        }
      } catch (RuntimeException e) {
        // Sigh: Collections swallows exceptions thrown here...
        e.printStackTrace();
        throw e;
      }
    }

    private List<AnnotationDescriptor> getAnnotationDescriptors(AnnotationsAttribute annotationsAttr) {
      List<AnnotationDescriptor> annotationDescriptors = new ArrayList<>(annotationsAttr.numAnnotations());
      for (javassist.bytecode.annotation.Annotation annotation : annotationsAttr.getAnnotations()) {
        // Sigh: javassist uses raw collections (is this 2002?)
        @SuppressWarnings("unchecked")
        Set<String> memberNames = annotation.getMemberNames();
        if (memberNames != null) {
          List<AttributeDescriptor> attributes = new ArrayList<>(memberNames.size());
          for (String name : memberNames) {
            attributes.add(new AttributeDescriptor(name, annotation.getMemberValue(name).toString()));
          }
          annotationDescriptors.add(new AnnotationDescriptor(annotation.getTypeName(), attributes));
        }
      }
      return annotationDescriptors;
    }
  }

  public static final class AttributeDescriptor {
    final String name;
    final String value;
    @JsonCreator
    public AttributeDescriptor(
        @JsonProperty("name")
        String name,
        @JsonProperty("value")
        String value) {
      this.name = name;
      this.value = value;
    }
    public String getName() {
      return name;
    }
    public String getValue() {
      return value;
    }
    @Override
    public String toString() {
      return "Attribute[" + name + "=" + value + "]";
    }
  }

  public static final class AnnotationDescriptor {
    final String annotationType;
    final List<AttributeDescriptor> attributes;
    @JsonCreator
    public AnnotationDescriptor(
        @JsonProperty("annotationType")
        String annotationType,
        @JsonProperty("attributes")
        List<AttributeDescriptor> attributes) {
      this.annotationType = annotationType;
      this.attributes = attributes;
    }
    public String getAnnotationType() {
      return annotationType;
    }
    public List<AttributeDescriptor> getAttributes() {
      return attributes;
    }
    @Override
    public String toString() {
      return "Annotation[type=" + annotationType + ", attributes=" + attributes + "]";
    }
  }

  public static final class FieldDescriptor {
    final String name;
    final String descriptor;
    final List<AnnotationDescriptor> annotations;
    @JsonCreator
    public FieldDescriptor(
        @JsonProperty("name")
        String name,
        @JsonProperty("descriptor")
        String descriptor,
        @JsonProperty("annotations")
        List<AnnotationDescriptor> annotations) {
      this.name = name;
      this.descriptor = descriptor;
      this.annotations = annotations;
    }
    public String getName() {
      return name;
    }
    public String getDescriptor() {
      return descriptor;
    }
    public List<AnnotationDescriptor> getAnnotations() {
      return annotations;
    }
    @Override
    public String toString() {
      return "Field[name=" + name + ", descriptor=" + descriptor + ", annotations=" + annotations + "]";
    }
  }

  public static final class FunctionDescriptor {
    final String className;
    final List<AnnotationDescriptor> annotations;
    final List<FieldDescriptor> fields;
    @JsonCreator
    public FunctionDescriptor(
        @JsonProperty("className")
        String className,
        @JsonProperty("annotations")
        List<AnnotationDescriptor> annotations,
        @JsonProperty("fields")
        List<FieldDescriptor> fields) {
      this.className = className;
      this.annotations = Collections.unmodifiableList(annotations);
      this.fields = Collections.unmodifiableList(fields);
    }
    public String getClassName() {
      return className;
    }
    public List<AnnotationDescriptor> getAnnotations() {
      return annotations;
    }
    public List<FieldDescriptor> getFields() {
      return fields;
    }
    @Override
    public String toString() {
      return "Function [className=" + className + ", annotations=" + annotations
          + ", fields=" + fields + "]";
    }
  }

  public static final class ScanResult {
    final List<String> prescannedURLs;
    final List<String> scannedURLs;
    final List<FunctionDescriptor> functions;
    @JsonCreator
    public ScanResult(
        @JsonProperty("prescannedURLs")
        List<String> prescannedURLs,
        @JsonProperty("scannedURLs")
        List<String> scannedURLs,
        @JsonProperty("functions")
        List<FunctionDescriptor> functions) {
      super();
      this.prescannedURLs = prescannedURLs;
      this.scannedURLs = scannedURLs;
      this.functions = functions;
    }
    public List<String> getPrescannedURLs() {
      return prescannedURLs;
    }
    public List<String> getScannedURLs() {
      return scannedURLs;
    }
    public List<FunctionDescriptor> getFunctions() {
      return functions;
    }
    @Override
    public String toString() {
      return "ScanResult [prescannedURLs=" + prescannedURLs + ", scannedURLs=" + scannedURLs + ", functions=" + functions
          + "]";
    }
    public ScanResult merge(ScanResult other) {
      final List<String> newPrescannedURLs = new ArrayList<>(prescannedURLs);
      newPrescannedURLs.addAll(other.prescannedURLs);
      final List<String> newScannedURLs = new ArrayList<>(scannedURLs);
      newScannedURLs.addAll(other.scannedURLs);
      final List<FunctionDescriptor> newFunctions = new ArrayList<>(functions);
      newFunctions.addAll(other.functions);
      return new ScanResult(newPrescannedURLs, newScannedURLs, newFunctions);
    }
  }

  static Collection<URL> getMarkedPaths() {
    return PathScanner.forResource(CommonConstants.DRILL_JAR_MARKER_FILE_RESOURCE_PATHNAME, true);
  }

  static Collection<URL> getPrescannedPaths() {
    return PathScanner.forResource(FUNCTION_REGISTRY_FILE, true);
  }

  static Collection<URL> getNonPrescannedMarkedPaths() {
    Collection<URL> markedPaths = getMarkedPaths();
    markedPaths.removeAll(getPrescannedPaths());
    return markedPaths;
  }

  static ScanResult fromFullScan() {
    return scan(getMarkedPaths());
  }

  private static List<String> mapToString(Collection<? extends Object> l) {
    List<String> result = new ArrayList<>(l.size());
    for (Object o : l) {
      result.add(o.toString());
    }
    return result;
  }

  static ScanResult scan(Collection<URL> pathsToScan) {
    long t0 = System.currentTimeMillis();
    try {
      FunctionResolver.FunctionScanner functionScanner = new FunctionResolver.FunctionScanner();
      ConfigurationBuilder conf = new ConfigurationBuilder()
          .setUrls(pathsToScan)
          .setMetadataAdapter(new JavassistAdapter()) // FunctionScanner depends on this
          .setScanners(functionScanner);
      new Reflections(conf); // scans stuff, but don't use the funky storage layer
      return new ScanResult(Collections.<String>emptyList(), mapToString(pathsToScan), functionScanner.functions);
    } finally {
      long t1 = System.currentTimeMillis();
      logger.debug("scanning " + pathsToScan + " took " + (t1 - t0) + "ms");
    }
  }

  public static ScanResult fromPrescan() {
    ScanResult prescanned = load();
    Set<String> prescannedURLs = new HashSet<>();
    if (prescanned != null) {
      prescannedURLs.addAll(prescanned.scannedURLs);
      prescannedURLs.addAll(prescanned.prescannedURLs);
      prescanned = new ScanResult(
          new ArrayList<>(prescannedURLs),
          Collections.<String>emptyList(),
          prescanned.functions);
    }
    Collection<URL> toScan = getNonPrescannedMarkedPaths();
    for (Iterator<URL> iterator = toScan.iterator(); iterator.hasNext();) {
      URL url = iterator.next();
      if (prescannedURLs.contains(url.toString())) {
        iterator.remove();
      }
    }
    ScanResult others = scan(toScan);
    ScanResult merged = prescanned == null ? others : prescanned.merge(others);
    return merged;
  }

  private static void save(ScanResult scanResult, File file) {
    try {
      writer.writeValue(file, scanResult);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static ScanResult load() {
    Set<URL> preScanned = PathScanner.forResource(FUNCTION_REGISTRY_FILE, false);
    ScanResult result = null;
    for (URL u : preScanned) {
      try (InputStream reflections = u.openStream()) {
        ScanResult ref = reader.readValue(reflections);
        if (result == null) {
          result = ref;
        } else {
          result = result.merge(ref);
        }
      } catch (IOException e) {
        throw new RuntimeException("can't read function registry at " + u, e);
      }
    }
    return result;
  }

  public static void main(String[] args) {
    if (args.length != 1) {
      throw new IllegalArgumentException("Usage: java {cp} " + PathScanner.class.getName() + " path/to/generate");
    }
    String basePath = args[0];
    File reflectionsFile = new File(basePath, FUNCTION_REGISTRY_FILE);
    File dir = reflectionsFile.getParentFile();
    if ((!dir.exists() && !dir.mkdirs()) || !dir.isDirectory()) {
      throw new IllegalArgumentException("could not create dir " + dir.getAbsolutePath());
    }
    save(fromFullScan(), reflectionsFile);
  }

}
