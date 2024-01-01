package org.apache.skywalking.graal;

import com.google.common.base.Strings;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.skywalking.mal.rt.kernel.MALKernel;
import org.apache.skywalking.oap.meter.analyzer.prometheus.rule.Rule;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Mojo(name = "mal-generate", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class MALGeneratorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    private Yaml yaml = new Yaml();
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        String targetDirectory = project.getBuild().getDirectory();
        MALKernel.generatedPath = Path.of(targetDirectory  +  File.separator +  "generated");
        Path start = ResourceUtils.getPath("otel-rules");
        List<String> rules = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(start)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".yml") || path.toString().endsWith(".yaml"))
                    .forEach(path -> {
                        try {
                            Rule rule = yaml.loadAs(Files.newBufferedReader(path), Rule.class);
                            rule.getMetricsRules().forEach( thisRule ->
                                    rules.add(formatExp(rule.getExpPrefix(), rule.getExpSuffix(), thisRule.getExp()))
                            );

                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String formatExp(final String expPrefix, String expSuffix, String exp) {
        String ret = exp;
        if (!Strings.isNullOrEmpty(expPrefix)) {
            ret = String.format("(%s.%s)", StringUtils.substringBefore(exp, "."), expPrefix);
            final String after = StringUtils.substringAfter(exp, ".");
            if (!Strings.isNullOrEmpty(after)) {
                ret = String.format("(%s.%s)", ret, after);
            }
        }
        if (!Strings.isNullOrEmpty(expSuffix)) {
            ret = String.format("(%s).%s", ret, expSuffix);
        }
        return ret;
    }
}
