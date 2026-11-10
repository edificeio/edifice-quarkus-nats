package io.edifice.nats.it.contract;

/**
 * A request of the test contract, shaped like a generated DTO.
 *
 * @param message the text sent
 */
public record EchoRequest(String message) {
}
