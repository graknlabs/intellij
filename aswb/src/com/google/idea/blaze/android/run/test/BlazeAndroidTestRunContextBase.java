/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.run.test;

import static com.google.idea.blaze.android.run.binary.BlazeAndroidBinaryNormalBuildRunContextBase.getFilesToInstall;

import com.android.ddmlib.IDevice;
import com.android.tools.idea.run.ApkProvider;
import com.android.tools.idea.run.ApplicationIdProvider;
import com.android.tools.idea.run.ConsolePrinter;
import com.android.tools.idea.run.ConsoleProvider;
import com.android.tools.idea.run.LaunchOptions;
import com.android.tools.idea.run.tasks.DeployTask;
import com.android.tools.idea.run.tasks.LaunchTask;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.idea.blaze.android.run.binary.mobileinstall.BlazeApkBuildStepMobileInstall;
import com.google.idea.blaze.android.run.deployinfo.BlazeApkProvider;
import com.google.idea.blaze.android.run.runner.BlazeAndroidDeviceSelector;
import com.google.idea.blaze.android.run.runner.BlazeAndroidRunContext;
import com.google.idea.blaze.android.run.runner.BlazeApkBuildStep;
import com.google.idea.blaze.android.run.runner.BlazeApkBuildStepNormalBuild;
import com.google.idea.blaze.android.run.runner.BlazeInstrumentationTestApkBuildStep;
import com.google.idea.blaze.android.run.test.BlazeAndroidTestLaunchMethodsProvider.AndroidTestLaunchMethod;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.run.BlazeCommandRunConfiguration;
import com.google.idea.blaze.base.run.smrunner.BlazeTestUiSession;
import com.google.idea.blaze.base.run.smrunner.TestUiSessionProvider;
import com.google.idea.blaze.java.AndroidBlazeRules;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.util.List;
import javax.annotation.Nullable;
import org.jetbrains.android.facet.AndroidFacet;

/** Run context for android_test. */
abstract class BlazeAndroidTestRunContextBase implements BlazeAndroidRunContext {
  protected final Project project;
  protected final AndroidFacet facet;
  protected final BlazeCommandRunConfiguration runConfiguration;
  protected final ExecutionEnvironment env;
  protected final BlazeAndroidTestRunConfigurationState configState;
  protected final Label label;
  protected final ImmutableList<String> blazeFlags;
  protected final List<Runnable> launchTaskCompleteListeners = Lists.newArrayList();
  protected final ConsoleProvider consoleProvider;
  protected final BlazeApkBuildStep buildStep;
  protected final ApplicationIdProvider applicationIdProvider;
  protected final ApkProvider apkProvider;

  BlazeAndroidTestRunContextBase(
      Project project,
      AndroidFacet facet,
      BlazeCommandRunConfiguration runConfiguration,
      ExecutionEnvironment env,
      BlazeAndroidTestRunConfigurationState configState,
      Label label,
      ImmutableList<String> blazeFlags,
      ImmutableList<String> exeFlags) {
    this.project = project;
    this.facet = facet;
    this.runConfiguration = runConfiguration;
    this.env = env;
    this.label = label;
    this.configState = configState;

    if (configState.getLaunchMethod().equals(AndroidTestLaunchMethod.MOBILE_INSTALL)) {
      this.buildStep = new BlazeApkBuildStepMobileInstall(project, label, blazeFlags, exeFlags);
    } else if (runConfiguration.getTargetKind()
        == AndroidBlazeRules.RuleTypes.ANDROID_INSTRUMENTATION_TEST.getKind()) {
      // android_instrumentation_test builds both test and app target APKs.
      this.buildStep = new BlazeInstrumentationTestApkBuildStep(project, label, blazeFlags);
    } else {
      this.buildStep = new BlazeApkBuildStepNormalBuild(project, label, blazeFlags);
    }

    this.applicationIdProvider = new BlazeAndroidTestApplicationIdProvider(buildStep);
    this.apkProvider = new BlazeApkProvider(project, buildStep);

    BlazeTestUiSession testUiSession =
        canUseTestUi(env.getExecutor())
            ? TestUiSessionProvider.getInstance(env.getProject())
                .getTestUiSession(ImmutableList.of(label))
            : null;
    if (testUiSession != null) {
      this.blazeFlags =
          ImmutableList.<String>builder()
              .addAll(testUiSession.getBlazeFlags())
              .addAll(blazeFlags)
              .build();
    } else {
      this.blazeFlags = blazeFlags;
    }
    this.consoleProvider =
        new AndroidTestConsoleProvider(project, runConfiguration, configState, testUiSession);
  }

  private static boolean canUseTestUi(Executor executor) {
    return !isDebugging(executor);
  }

  private static boolean isDebugging(Executor executor) {
    return executor instanceof DefaultDebugExecutor;
  }

  @Override
  public BlazeAndroidDeviceSelector getDeviceSelector() {
    return new BlazeAndroidDeviceSelector.NormalDeviceSelector();
  }

  @Override
  public void augmentLaunchOptions(LaunchOptions.Builder options) {
    options.setDeploy(!configState.getLaunchMethod().equals(AndroidTestLaunchMethod.BLAZE_TEST));
  }

  @Override
  public ConsoleProvider getConsoleProvider() {
    return consoleProvider;
  }

  @Override
  public ApplicationIdProvider getApplicationIdProvider() throws ExecutionException {
    return applicationIdProvider;
  }

  @Nullable
  @Override
  public BlazeApkBuildStep getBuildStep() {
    return buildStep;
  }

  @Override
  public ImmutableList<LaunchTask> getDeployTasks(IDevice device, LaunchOptions launchOptions)
      throws ExecutionException {
    switch (configState.getLaunchMethod()) {
      case NON_BLAZE:
        // fall through
      case BLAZE_TEST:
        ImmutableMap<String, List<File>> filesToInstall =
            getFilesToInstall(device, launchOptions, apkProvider);
        return ImmutableList.of(
            new DeployTask(project, filesToInstall, launchOptions.getPmInstallOptions()));
      case MOBILE_INSTALL:
        return ImmutableList.of();
    }
    throw new AssertionError();
  }

  void onLaunchTaskComplete() {
    for (Runnable runnable : launchTaskCompleteListeners) {
      runnable.run();
    }
  }

  void addLaunchTaskCompleteListener(Runnable runnable) {
    launchTaskCompleteListeners.add(runnable);
  }

  @Nullable
  @Override
  public Integer getUserId(IDevice device, ConsolePrinter consolePrinter) {
    return null;
  }
}
