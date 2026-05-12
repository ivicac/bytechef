package com.agui.core.event;

import com.agui.core.type.EventType;

/**
 * An event that represents an error that occurred during a run or execution.
 * <p>
 * This event is fired when an error occurs during the execution of a process,
 * operation, or run cycle. It captures the error information to enable proper
 * error handling and reporting throughout the application.
 * </p>
 * <p>
 * The event automatically sets its type to {@link EventType#RUN_ERROR} and
 * provides a field to store the error message or description.
 * </p>
 *
 * <p>
 * <strong>Wire-format note:</strong> the AG-UI protocol (the TypeScript core's
 * {@code RunErrorEventSchema}) names this field {@code message}, not {@code error}.
 * The on-the-wire rename is applied via {@link com.agui.json.mixins.RunErrorEventMixin}
 * in the {@code ag-ui/utils/json} module so this {@code core} module stays free of any
 * Jackson dependency. Without the rename the Java server emits
 * {@code {"type":"RUN_ERROR","error":"…"}} and the browser-side Zod validator rejects
 * the event with a ZodError ({@code path: ["message"], message: "Required"}) the
 * moment any agent run fails — masking the real failure with a meta-failure to
 * deserialize the failure event.
 * </p>
 *
 * @see BaseEvent
 * @see EventType#RUN_ERROR
 *
 * @author Pascal Wilbrink
 */
public class RunErrorEvent extends BaseEvent {

    private String error;

    /**
     * Creates a new RunErrorEvent with type set to {@link EventType#RUN_ERROR}.
     * <p>
     * The timestamp is automatically set to the current time and the error
     * field is initialized as null.
     * </p>
     */
    public RunErrorEvent() {
        super(EventType.RUN_ERROR);
    }

    /**
     * Sets the error message or description for this event.
     *
     * @param error the error message or description. Can be null.
     */
    public void setError(final String error) {
        this.error = error;
    }

    /**
     * Returns the error message or description associated with this event.
     *
     * @return the error message, can be null
     */
    public String getError() {
        return this.error;
    }
}