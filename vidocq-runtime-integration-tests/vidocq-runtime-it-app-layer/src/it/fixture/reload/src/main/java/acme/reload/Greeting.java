package acme.reload;

/** The JSON body of {@link GreetingResource}. */
public record Greeting(String salutation, String name) {
}
