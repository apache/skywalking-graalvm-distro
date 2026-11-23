/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.skywalking.oap.server.core.oal.rt;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.analysis.DisableRegister;
import org.apache.skywalking.oap.server.core.analysis.DispatcherDetectorListener;
import org.apache.skywalking.oap.server.core.analysis.StreamAnnotationListener;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.OalScript;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleProviderHolder;
import org.apache.skywalking.oap.server.library.module.ModuleServiceHolder;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The same-FQCN {@code OALEngineLoaderService} loads OAL per define, as upstream: a define registers its own compiled
 * script, its disables before its metrics and its metrics before its dispatchers; a define no provider loads has no
 * effect; the scripts the rules overlay added, which no provider loads, register at the first load, after the define
 * it loads. A disable may name a stream declared in code ({@code segment}) as well as an OAL metric.
 */
class OALEngineLoaderServiceTest {
    private static final String SOURCE_PACKAGE = "org.apache.skywalking.oap.server.core.source";
    private static final OALDefine CORE = define("oal/core.oal");
    private static final OALDefine BROWSER = define("oal/browser.oal");
    private static final OALDefine OVERLAY = define("oal/fleet.oal");
    private static final OALDefine DISABLE = define("oal/disable.oal");
    private static final OALDefine LATE = define("oal/late.oal");
    private static final OALDefine NEVER = define("oal/never.oal");
    // A stream declared in code (a hard-coded @Stream) and one an OAL script defines, as a disable can name either.
    private static final String CODE_STREAM = "segment";
    private static final String OAL_METRIC = "endpoint_cpm";

    private final List<String> events = new ArrayList<>();
    private MockedStatic<CompiledRules> rules;
    private MockedConstruction<StreamAnnotationListener> streamListeners;
    private DisableRegister originalDisables;
    private DisableRegister disables;
    private OALEngineLoaderService loader;

    @BeforeEach
    void mockTheRegistryAndTheListeners() throws Exception {
        rules = mockStatic(CompiledRules.class);
        rules.when(CompiledRules::oalScripts).thenReturn(List.of(
            new OalScript(CORE, List.of(String.class), List.of(Integer.class), List.of("core_disabled"), false),
            new OalScript(BROWSER, List.of(Long.class), List.of(Short.class), List.of("browser_disabled"), false),
            new OalScript(OVERLAY, List.of(Byte.class), List.of(Character.class), List.of(), true),
            new OalScript(DISABLE, List.of(), List.of(), List.of(CODE_STREAM, OAL_METRIC), false),
            new OalScript(LATE, List.of(), List.of(), List.of(CODE_STREAM, OAL_METRIC), false),
            new OalScript(NEVER, List.of(), List.of(), List.of(CODE_STREAM, OAL_METRIC), false)));
        rules.when(CompiledRules::oalExcludedScripts).thenReturn(List.of("oal/tcp.oal"));

        streamListeners = mockConstruction(StreamAnnotationListener.class, (listener, context) ->
            doAnswer(call -> events.add("stream " + call.<Class<?>>getArgument(0).getSimpleName()))
                .when(listener).notify(any()));
        originalDisables = DisableRegister.INSTANCE;
        disables = mock(DisableRegister.class);
        doAnswer(call -> events.add("disable " + call.getArgument(0))).when(disables).addFromScript(anyString());
        DisableRegister.INSTANCE = disables;

        final DispatcherDetectorListener dispatchers = mock(DispatcherDetectorListener.class);
        doAnswer(call -> events.add("dispatcher " + call.<Class<?>>getArgument(0).getSimpleName()))
            .when(dispatchers).addIfAsSourceDispatcher(any());
        final SourceReceiver receiver = mock(SourceReceiver.class);
        when(receiver.getDispatcherDetectorListener()).thenReturn(dispatchers);
        final ModuleServiceHolder core = mock(ModuleServiceHolder.class);
        when(core.getService(SourceReceiver.class)).thenReturn(receiver);
        final ModuleProviderHolder coreModule = mock(ModuleProviderHolder.class);
        when(coreModule.provider()).thenReturn(core);
        final ModuleManager manager = mock(ModuleManager.class);
        when(manager.find(CoreModule.NAME)).thenReturn(coreModule);
        loader = new OALEngineLoaderService(manager);
    }

    @AfterEach
    void restore() {
        DisableRegister.INSTANCE = originalDisables;
        streamListeners.close();
        rules.close();
    }

    @Test
    void theFirstDefineRegistersItsOwnScriptThenTheOverlayScripts() throws Exception {
        loader.load(CORE);
        assertEquals(List.of("disable core_disabled", "stream String", "dispatcher Integer",
            "stream Byte", "dispatcher Character"), events);
        assertEquals(Set.of(OVERLAY, CORE), loader.getLoadedDefines());
    }

    /**
     * Upstream's core loads {@code oal/disable.oal} first: its disables register before the streams of the scripts the
     * rules overlay added, so it can disable one of their metrics.
     */
    @Test
    void disableOalsDisablesComeBeforeTheOverlayScriptsStreams() throws Exception {
        loader.load(DISABLE);
        assertEquals(List.of("disable " + CODE_STREAM, "disable " + OAL_METRIC, "stream Byte", "dispatcher Character"),
            events);
    }

    @Test
    void aDefineNoProviderLoadsHasNoEffect() throws Exception {
        loader.load(CORE);
        assertTrue(events.stream().noneMatch(e -> e.contains("browser") || e.equals("stream Long")
            || e.equals("dispatcher Short")), events.toString());
    }

    @Test
    void aLaterDefineRegistersItsScriptOnly() throws Exception {
        loader.load(CORE);
        events.clear();
        loader.load(BROWSER);
        loader.load(CORE);
        assertEquals(List.of("disable browser_disabled", "stream Long", "dispatcher Short"), events);
        assertEquals(Set.of(OVERLAY, CORE, BROWSER), loader.getLoadedDefines());
    }

    @Test
    void aDisableOfARunningStreamIsSkipped() throws Exception {
        when(disables.isStreamRunning("browser_disabled")).thenReturn(true);
        loader.load(CORE);
        loader.load(BROWSER);
        assertTrue(events.contains("disable core_disabled"), events.toString());
        assertTrue(events.stream().noneMatch("disable browser_disabled"::equals), events.toString());
    }

    /**
     * As upstream's core start: disable.oal loads before the annotation scan defines the streams declared in code, and
     * before the OAL metrics of later defines, so its disables skip them.
     */
    @Test
    void disableOalsDisablesSkipTheStreamsDefinedAfterThem() throws Exception {
        final DisableRegister register = realDisableRegister();
        loader.load(DISABLE);
        register.markStreamDefined(CODE_STREAM);
        register.markStreamDefined(OAL_METRIC);

        assertTrue(register.include(CODE_STREAM) && register.include(OAL_METRIC));
        assertFalse(register.isStreamRunning(CODE_STREAM) || register.isStreamRunning(OAL_METRIC));
    }

    /**
     * A disable loaded after its stream runs is not registered, upstream's warning aside, so nothing that asks the
     * register later (the profile task cache's {@code include}) sees half of it.
     */
    @Test
    void aDisableLoadedAfterItsStreamRunsHasNoEffect() throws Exception {
        final DisableRegister register = realDisableRegister();
        register.markStreamDefined(CODE_STREAM);
        register.markStreamDefined(OAL_METRIC);
        loader.load(LATE);

        assertFalse(register.include(CODE_STREAM) || register.include(OAL_METRIC));
        assertTrue(register.isStreamRunning(CODE_STREAM) && register.isStreamRunning(OAL_METRIC));
    }

    @Test
    void theDisablesOfADefineThatNeverLoadsAffectNothing() throws Exception {
        final DisableRegister register = realDisableRegister();
        loader.load(CORE);
        register.markStreamDefined(CODE_STREAM);
        register.markStreamDefined(OAL_METRIC);

        assertFalse(register.include(CODE_STREAM) || register.include(OAL_METRIC));
        assertTrue(register.isStreamRunning(CODE_STREAM) && register.isStreamRunning(OAL_METRIC));
        assertFalse(loader.getLoadedDefines().contains(NEVER));
    }

    @Test
    void aDefineTheRulesOverlayExcludedRegistersNothing() throws Exception {
        loader.load(define("oal/tcp.oal"));
        assertEquals(List.of("stream Byte", "dispatcher Character"), events);
        assertEquals(Set.of(OVERLAY), loader.getLoadedDefines());
    }

    @Test
    void aDefineNeitherCompiledNorExcludedFailsTheBoot() {
        final ModuleStartException e = assertThrows(ModuleStartException.class,
            () -> loader.load(define("oal/unknown.oal")));
        assertTrue(e.getMessage().contains("oal/unknown.oal"), e.getMessage());
    }

    @Test
    void theLoadedDefinesCannotBeChanged() throws Exception {
        loader.load(CORE);
        assertThrows(UnsupportedOperationException.class, () -> loader.getLoadedDefines().clear());
    }

    /**
     * @return a fresh register in place of the mock, as {@code DisableRegister.INSTANCE}
     */
    private static DisableRegister realDisableRegister() throws Exception {
        final Constructor<DisableRegister> constructor = DisableRegister.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        DisableRegister.INSTANCE = constructor.newInstance();
        return DisableRegister.INSTANCE;
    }

    private static OALDefine define(final String configFile) {
        return new OALDefine(configFile, SOURCE_PACKAGE) {
        };
    }
}
