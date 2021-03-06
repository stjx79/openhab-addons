/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.automation.pidcontroller.internal.handler;

import static org.openhab.automation.pidcontroller.internal.PIDControllerConstants.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.automation.pidcontroller.internal.PIDException;
import org.openhab.core.automation.ModuleHandlerCallback;
import org.openhab.core.automation.Trigger;
import org.openhab.core.automation.handler.BaseTriggerModuleHandler;
import org.openhab.core.automation.handler.TriggerHandlerCallback;
import org.openhab.core.common.NamedThreadFactory;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventFilter;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.events.EventSubscriber;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.items.events.ItemStateChangedEvent;
import org.openhab.core.items.events.ItemStateEvent;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Hilbrand Bouwkamp - Initial Contribution
 * @author Fabian Wolter - Add PID debug output values
 */
@NonNullByDefault
public class PIDControllerTriggerHandler extends BaseTriggerModuleHandler implements EventSubscriber {
    public static final String MODULE_TYPE_ID = AUTOMATION_NAME + ".trigger";
    private static final Set<String> SUBSCRIBED_EVENT_TYPES = Set.of(ItemStateEvent.TYPE, ItemStateChangedEvent.TYPE);
    private final Logger logger = LoggerFactory.getLogger(PIDControllerTriggerHandler.class);
    private final ScheduledExecutorService scheduler = Executors
            .newSingleThreadScheduledExecutor(new NamedThreadFactory("OH-automation-" + AUTOMATION_NAME, true));
    private final ServiceRegistration<?> eventSubscriberRegistration;
    private final PIDController controller;
    private final int loopTimeMs;
    private @Nullable ScheduledFuture<?> controllerjob;
    private long previousTimeMs = System.currentTimeMillis();
    private Item inputItem;
    private Item setpointItem;
    private EventFilter eventFilter;

    public PIDControllerTriggerHandler(Trigger module, ItemRegistry itemRegistry, EventPublisher eventPublisher,
            BundleContext bundleContext) {
        super(module);

        Configuration config = module.getConfiguration();

        String inputItemName = (String) requireNonNull(config.get(CONFIG_INPUT_ITEM), "Input item is not set");
        String setpointItemName = (String) requireNonNull(config.get(CONFIG_SETPOINT_ITEM), "Setpoint item is not set");

        try {
            inputItem = itemRegistry.getItem(inputItemName);
        } catch (ItemNotFoundException e) {
            throw new IllegalArgumentException("Configured input item not found: " + inputItemName, e);
        }

        try {
            setpointItem = itemRegistry.getItem(setpointItemName);
        } catch (ItemNotFoundException e) {
            throw new IllegalArgumentException("Configured setpoint item not found: " + setpointItemName, e);
        }

        double outputLowerLimit = getDoubleFromConfig(config, CONFIG_OUTPUT_LOWER_LIMIT);
        double outputUpperLimit = getDoubleFromConfig(config, CONFIG_OUTPUT_UPPER_LIMIT);
        double kpAdjuster = getDoubleFromConfig(config, CONFIG_KP_GAIN);
        double kiAdjuster = getDoubleFromConfig(config, CONFIG_KI_GAIN);
        double kdAdjuster = getDoubleFromConfig(config, CONFIG_KD_GAIN);
        double kdTimeConstant = getDoubleFromConfig(config, CONFIG_KD_TIMECONSTANT);

        loopTimeMs = ((BigDecimal) requireNonNull(config.get(CONFIG_LOOP_TIME), CONFIG_LOOP_TIME + " is not set"))
                .intValue();

        controller = new PIDController(outputLowerLimit, outputUpperLimit, kpAdjuster, kiAdjuster, kdAdjuster,
                kdTimeConstant);

        eventFilter = event -> {
            String topic = event.getTopic();

            return topic.equals("openhab/items/" + inputItemName + "/state")
                    || topic.equals("openhab/items/" + inputItemName + "/statechanged")
                    || topic.equals("openhab/items/" + setpointItemName + "/statechanged");
        };

        eventSubscriberRegistration = bundleContext.registerService(EventSubscriber.class.getName(), this, null);

        eventPublisher.post(ItemEventFactory.createCommandEvent(inputItemName, RefreshType.REFRESH));

        controllerjob = scheduler.scheduleWithFixedDelay(this::calculate, 0, loopTimeMs, TimeUnit.MILLISECONDS);
    }

    private <T> T requireNonNull(T obj, String message) {
        if (obj == null) {
            throw new IllegalArgumentException(message);
        }
        return obj;
    }

    private double getDoubleFromConfig(Configuration config, String key) {
        return ((BigDecimal) Objects.requireNonNull(config.get(key), key + " is not set")).doubleValue();
    }

    private void calculate() {
        double input;
        double setpoint;

        try {
            input = getItemValueAsNumber(inputItem);
        } catch (PIDException e) {
            logger.warn("Input item: {}", e.getMessage());
            return;
        }

        try {
            setpoint = getItemValueAsNumber(setpointItem);
        } catch (PIDException e) {
            logger.warn("Setpoint item: {}", e.getMessage());
            return;
        }

        long now = System.currentTimeMillis();

        PIDOutputDTO output = controller.calculate(input, setpoint, now - previousTimeMs);
        previousTimeMs = now;

        Map<String, BigDecimal> outputs = new HashMap<>();

        putBigDecimal(outputs, OUTPUT, output.getOutput());
        putBigDecimal(outputs, P_INSPECTOR, output.getProportionalPart());
        putBigDecimal(outputs, I_INSPECTOR, output.getIntegralPart());
        putBigDecimal(outputs, D_INSPECTOR, output.getDerivativePart());
        putBigDecimal(outputs, E_INSPECTOR, output.getError());

        ModuleHandlerCallback localCallback = callback;
        if (localCallback != null && localCallback instanceof TriggerHandlerCallback) {
            ((TriggerHandlerCallback) localCallback).triggered(module, outputs);
        } else {
            logger.warn("No callback set");
        }
    }

    private void putBigDecimal(Map<String, BigDecimal> map, String key, double value) {
        map.put(key, BigDecimal.valueOf(value));
    }

    private double getItemValueAsNumber(Item item) throws PIDException {
        State setpointState = item.getState();

        if (setpointState instanceof Number) {
            double doubleValue = ((Number) setpointState).doubleValue();

            if (Double.isFinite(doubleValue)) {
                return doubleValue;
            }
        } else if (setpointState instanceof StringType) {
            try {
                return Double.parseDouble(setpointState.toString());
            } catch (NumberFormatException e) {
                // nothing
            }
        }
        throw new PIDException(
                "Item type is not a number: " + setpointState.getClass().getSimpleName() + ": " + setpointState);
    }

    @Override
    public void receive(Event event) {
        if (event instanceof ItemStateChangedEvent) {
            calculate();
        }
    }

    @Override
    public Set<String> getSubscribedEventTypes() {
        return SUBSCRIBED_EVENT_TYPES;
    }

    @Override
    public @Nullable EventFilter getEventFilter() {
        return eventFilter;
    }

    @Override
    public void dispose() {
        eventSubscriberRegistration.unregister();

        ScheduledFuture<?> localControllerjob = controllerjob;
        if (localControllerjob != null) {
            localControllerjob.cancel(true);
        }

        super.dispose();
    }
}
