package com.ss.android.ugc.bytex.common;

import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.SecondaryFile;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.ss.android.ugc.bytex.common.flow.TransformFlow;
import com.ss.android.ugc.bytex.common.flow.main.MainTransformFlow;
import com.ss.android.ugc.bytex.common.graph.Graph;
import com.ss.android.ugc.bytex.common.log.LevelLog;
import com.ss.android.ugc.bytex.common.log.Timer;
import com.ss.android.ugc.bytex.common.log.html.HtmlReporter;
import com.ss.android.ugc.bytex.gradletoolkit.TransformInvocationKt;
import com.ss.android.ugc.bytex.transformer.TransformContext;
import com.ss.android.ugc.bytex.transformer.TransformEngine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created by tlh on 2018/8/29.
 */

public abstract class CommonTransform<X extends BaseContext> extends Transform {
    protected X context;
    private Set<TransformConfiguration> configurations;

    public CommonTransform(X context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return context.extension.getName();
    }

    @Override
    public Set<QualifiedContent.ContentType> getInputTypes() {
        Set<QualifiedContent.ContentType> result = ImmutableSet.of();
        for (TransformConfiguration config : getConfigurations()) {
            Set<QualifiedContent.ContentType> inputTypes = config.getInputTypes();
            if (!result.containsAll(inputTypes)) {
                result = Sets.union(result, inputTypes);
            }
        }
        if (result.isEmpty()) {
            return TransformManager.CONTENT_CLASS;
        }
        return result;
    }

    @Override
    public Set<? super QualifiedContent.Scope> getScopes() {
        Set<? super QualifiedContent.Scope> result = ImmutableSet.of();
        for (TransformConfiguration config : getConfigurations()) {
            Set<? super QualifiedContent.Scope> scopes = config.getScopes();
            if (!result.containsAll(scopes)) {
                result = Sets.union(result, scopes);
            }
        }
        return result;
    }

    @Override
    public Set<QualifiedContent.ContentType> getOutputTypes() {
        Set<QualifiedContent.ContentType> result = super.getOutputTypes();
        for (TransformConfiguration config : getConfigurations()) {
            Set<QualifiedContent.ContentType> outputTypes = config.getOutputTypes();
            if (!result.containsAll(outputTypes)) {
                result = Sets.union(result, outputTypes);
            }
        }
        return result;
    }

    @Override
    public Set<? super QualifiedContent.Scope> getReferencedScopes() {
        Set<? super QualifiedContent.Scope> result = super.getReferencedScopes();
        for (TransformConfiguration config : getConfigurations()) {
            Set<? super QualifiedContent.Scope> referencedScopes = config.getReferencedScopes();
            if (!result.containsAll(referencedScopes)) {
                result = Sets.union(result, referencedScopes);
            }
        }
        if (result.isEmpty()) {
            return TransformManager.SCOPE_FULL_PROJECT;
        }
        return result;
    }

    @Override
    public Collection<SecondaryFile> getSecondaryFiles() {
        Collection<SecondaryFile> result = new ArrayList<>(super.getSecondaryFiles());
        for (TransformConfiguration config : getConfigurations()) {
            Collection<SecondaryFile> secondaryFiles = config.getSecondaryFiles();
            for (SecondaryFile file : secondaryFiles) {
                if (file != null && !result.contains(file)) {
                    result.add(file);
                }
            }
        }
        return ImmutableList.copyOf(result);
    }

    @Override
    public Collection<File> getSecondaryFileOutputs() {
        Collection<File> result = new ArrayList<>(super.getSecondaryFileOutputs());
        for (TransformConfiguration config : getConfigurations()) {
            Collection<File> secondaryFiles = config.getSecondaryFileOutputs();
            for (File file : secondaryFiles) {
                if (file != null && !result.contains(file)) {
                    result.add(file);
                }
            }
        }
        return ImmutableList.copyOf(result);
    }


    @Override
    public Collection<File> getSecondaryDirectoryOutputs() {
        Collection<File> result = new ArrayList<>(super.getSecondaryDirectoryOutputs());
        for (TransformConfiguration config : getConfigurations()) {
            Collection<File> outputs = config.getSecondaryDirectoryOutputs();
            for (File file : outputs) {
                if (file != null && !result.contains(file)) {
                    result.add(file);
                }
            }
        }
        return ImmutableList.copyOf(result);
    }

    @Override
    public Map<String, Object> getParameterInputs() {
        Map<String, Object> result = new HashMap<>(super.getParameterInputs());
        for (TransformConfiguration config : getConfigurations()) {
            Map<String, Object> parameterInputs = config.getParameterInputs();
            result.putAll(parameterInputs);
        }
        return ImmutableMap.copyOf(result);
    }

    @Override
    public boolean isIncremental() {
        boolean result = true;
        for (TransformConfiguration config : getConfigurations()) {
            if (!config.isIncremental()) {
                result = false;
                break;
            }
        }
        return result;
    }

    public boolean shouldSaveCache() {
        return getPlugins().stream().allMatch(IPlugin::shouldSaveCache);
    }

    @Override
    public final void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        super.transform(transformInvocation);
        init(transformInvocation);
        TransformContext transformContext = getTransformContext(transformInvocation);
        List<IPlugin> plugins = getPlugins().stream().filter(p -> p.enable(transformContext)).collect(Collectors.toList());

        Timer timer = new Timer();
        TransformEngine transformEngine = new TransformEngine(transformContext);

        try {
            if (!plugins.isEmpty()) {
                Queue<TransformFlow> flowSet = new PriorityQueue<>((o1, o2) -> o2.getPriority() - o1.getPriority());
                MainTransformFlow commonFlow = new MainTransformFlow(transformEngine);
//                flowSet.add(commonFlow);
                for (int i = 0; i < plugins.size(); i++) {
                    IPlugin plugin = plugins.get(i);
                    TransformFlow flow = plugin.registerTransformFlow(commonFlow, transformContext);
                    if (!flowSet.contains(flow)) {
                        flowSet.add(flow);
                    }
                }
                while (!flowSet.isEmpty()) {
                    TransformFlow flow = flowSet.poll();
                    if (flow != null) {
                        if (flowSet.size() == 0) {
                            flow.asTail();
                        }
                        flow.run();
                        Graph graph = flow.getClassGraph();
                        if (graph != null) {
                            //clear the class diagram.we won’t use it anymore
                            graph.clear();
                        }
                    }
                }
            } else {
                transformEngine.skip();
            }
        } catch (Throwable throwable) {
            LevelLog.sDefaultLogger.e(throwable.getClass().getName(), throwable);
            throw throwable;
        } finally {
            timer.record("Total cost time = [%s ms]");
            HtmlReporter.getInstance().createHtmlReporter(getName());
            HtmlReporter.getInstance().reset();
        }
        afterTransform(transformInvocation);
    }

    protected TransformContext getTransformContext(TransformInvocation transformInvocation) {
        return new TransformContext(transformInvocation, context.project, context.android, isIncremental(), shouldSaveCache());
    }

    protected void afterTransform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
    }

    protected void init(TransformInvocation transformInvocation) {
        context.init();
        String applicationId = "unknow";
        String versionName = "unknow";
        String versionCode = "unknow";
        com.android.builder.model.ProductFlavor flavor = TransformInvocationKt.getVariant(transformInvocation).getMergedFlavor();
        if (flavor != null) {
            String flavorApplicationId = flavor.getApplicationId();
            if (flavorApplicationId != null && !flavorApplicationId.isEmpty()) {
                applicationId = flavorApplicationId;
            }
            String flavorVersionName = flavor.getVersionName();
            if (flavorVersionName != null && !flavorVersionName.isEmpty()) {
                versionName = flavorVersionName;
            }
            Integer flavorVersionCode = flavor.getVersionCode();
            if (flavorVersionCode != null) {
                versionCode = String.valueOf(flavorVersionCode);
            }
        }
        HtmlReporter.getInstance().init(
                new File(context.project.getBuildDir(), "ByteX").getAbsolutePath(),
                "ByteX",
                applicationId,
                versionName,
                versionCode
        );

        LevelLog.sDefaultLogger = context.getLogger();
    }

    protected abstract List<IPlugin> getPlugins();

    private Set<TransformConfiguration> getConfigurations() {
        if (configurations == null) {
            this.configurations = getPlugins().stream().map(IPlugin::transformConfiguration).collect(Collectors.toSet());
        }
        return this.configurations;
    }
}
