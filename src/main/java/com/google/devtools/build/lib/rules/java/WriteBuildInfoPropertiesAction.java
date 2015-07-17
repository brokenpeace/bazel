// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.java;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.analysis.BuildInfoHelper;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction.Key;
import com.google.devtools.build.lib.analysis.actions.AbstractFileWriteAction;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.util.Fingerprint;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * An action that creates a Java properties file containing the build informations.
 */
public class WriteBuildInfoPropertiesAction extends AbstractFileWriteAction {
  private static final String GUID = "922949ca-1391-4046-a300-74810618dcdc";

  private final ImmutableList<Artifact> valueArtifacts;
  private final BuildInfoPropertiesTranslator keyTranslations;
  private final boolean includeVolatile;
  private final boolean includeNonVolatile;
  
  private final TimestampFormatter timestampFormatter;
  /**
   * An interface to format a timestamp. We are using our custom one to avoid external dependency.
   */
  public static interface TimestampFormatter {
    /**
     * Return a human readable string for the given {@code timestamp}. {@code timestamp} is given
     * in milliseconds since 1st of January 1970 at 0am UTC.
     */
    public String format(long timestamp);
  }
  
  /**
   * A wrapper around a {@link Writer} that skips the first line assuming the line is pure ASCII. It
   * can be used to strip the timestamp comment that {@link Properties#store(Writer, String)} adds.
   */
  @VisibleForTesting
  static class StripFirstLineWriter extends Writer {
    private final Writer writer;
    private boolean newlineFound = false;

    StripFirstLineWriter(OutputStream out) {
      this.writer = new OutputStreamWriter(out, UTF_8);
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
      if (!newlineFound) {
        while (len > 0 && cbuf[off] != '\n') {
          off++;
          len--;
        }
        if (len > 0) {
          newlineFound = true;
          off++;
          len--;
        }
      }
      if (len > 0) {
        writer.write(cbuf, off, len);
      }
    }

    @Override
    public void flush() throws IOException {
      writer.flush();
    }

    @Override
    public void close() throws IOException {
      writer.close();
    }

  }

  /**
   * Creates an action that writes a Java property files with build information.
   *
   * <p>It reads the set of build info keys from an action context that is usually contributed to
   * Blaze by the workspace status module, and the value associated with said keys from the
   * workspace status files (stable and volatile) written by the workspace status action. The files
   * generated by this action serve as input to the
   * {@link com.google.devtools.build.singlejar.SingleJar} program.
   *
   * <p>Without input artifacts, this action uses redacted build information.
   *
   * @param inputs Artifacts that contain build information, or an empty collection to use redacted
   *        build information
   * @param output output the properties file Artifact created by this action
   * @param keyTranslations how to translates available keys. See
   *        {@link BuildInfoPropertiesTranslator}.
   * @param includeVolatile whether the set of key to write are giving volatile keys or not
   * @param includeNonVolatile whether the set of key to write are giving non-volatile keys or not
   * @param timestampFormatter formats dates printed in the properties file
   */
  public WriteBuildInfoPropertiesAction(Collection<Artifact> inputs, Artifact output,
      BuildInfoPropertiesTranslator keyTranslations, boolean includeVolatile,
      boolean includeNonVolatile, TimestampFormatter timestampFormatter) {
    super(BuildInfoHelper.BUILD_INFO_ACTION_OWNER, inputs, output, /* makeExecutable= */false);
    this.keyTranslations = keyTranslations;
    this.includeVolatile = includeVolatile;
    this.includeNonVolatile = includeNonVolatile;
    this.timestampFormatter = timestampFormatter;
    valueArtifacts = ImmutableList.copyOf(inputs);

    if (!inputs.isEmpty()) {
      // With non-empty inputs we should not generate both volatile and non-volatile data
      // in the same properties file.
      Preconditions.checkState(includeVolatile ^ includeNonVolatile);
    }
    Preconditions.checkState(
        output.isConstantMetadata() == (includeVolatile && !inputs.isEmpty()));
  }

  @Override
  public DeterministicWriter newDeterministicWriter(EventHandler eventHandler,
                                                    final Executor executor) {
    final long timestamp = System.currentTimeMillis();
    return new DeterministicWriter() {
      @Override
      public void writeOutputFile(OutputStream out) throws IOException {
        WorkspaceStatusAction.Context context =
            executor.getContext(WorkspaceStatusAction.Context.class);
        Map<String, String> values = new LinkedHashMap<>();
        for (Artifact valueFile : valueArtifacts) {
          values.putAll(WorkspaceStatusAction.parseValues(valueFile.getPath()));
        }

        Map<String, String> keys = new HashMap<>();
        if (includeVolatile) {
          addValues(keys, values, context.getVolatileKeys());
          keys.put("BUILD_TIMESTAMP", Long.toString(timestamp / 1000));
          keys.put("BUILD_TIME", timestampFormatter.format(timestamp));
        }
        addValues(keys, values, context.getStableKeys());
        Properties properties = new Properties();
        keyTranslations.translate(keys, properties);
        properties.store(new StripFirstLineWriter(out), null);
      }
    };
  }

  private void addValues(Map<String, String> result, Map<String, String> values,
      Map<String, Key> keys) {
    boolean redacted = values.isEmpty();
    for (Map.Entry<String, WorkspaceStatusAction.Key> key : keys.entrySet()) {
      if (key.getValue().isInLanguage("Java")) {
        result.put(key.getKey(), gePropertyValue(values, redacted, key));
      }
    }
  }

  private static String gePropertyValue(Map<String, String> values, boolean redacted,
      Map.Entry<String, WorkspaceStatusAction.Key> key) {
    return redacted ? key.getValue().getRedactedValue()
        : values.containsKey(key.getKey()) ? values.get(key.getKey())
            : key.getValue().getDefaultValue();
  }

  @Override
  protected String computeKey() {
    Fingerprint f = new Fingerprint();
    f.addString(GUID);
    f.addString(keyTranslations.computeKey());
    f.addBoolean(includeVolatile);
    f.addBoolean(includeNonVolatile);
    return f.hexDigestAndReset();
  }

  @Override
  public boolean executeUnconditionally() {
    return isVolatile();
  }

  @Override
  public boolean isVolatile() {
    return includeVolatile && !Iterables.isEmpty(getInputs());
  }
}
