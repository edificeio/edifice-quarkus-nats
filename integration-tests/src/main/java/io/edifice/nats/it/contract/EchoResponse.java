package io.edifice.nats.it.contract;

import java.util.Map;

/**
 * A response of the test contract, shaped like a generated DTO.
 *
 * @param message the text answered
 * @param subject the subject the request was received on
 * @param values  extra values read by the listener
 */
public record EchoResponse(String message, String subject, Map<String, String> values) {
}
