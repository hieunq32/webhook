# Facebook Messenger Webhook POC for AI Recruitment Chatbot

Phase 1 POC for a recruitment assistant that:

1. Verifies Facebook Messenger webhook ownership with `GET /webhook`.
2. Receives candidate messages with `POST /webhook`.
3. Sends the candidate text to OpenAI with a recruitment-assistant system prompt.
4. Sends the AI reply back through the Facebook Messenger Send API.

## Tech stack

- Java 17
- Spring Boot 3
- Spring MVC REST API
- `RestTemplate` for outbound HTTP calls

## Project structure

```text
.
├── pom.xml
├── .gitignore
├── README.md
└── src
    └── main
        ├── java
        │   └── com
        │       └── example
        │           └── recruitmentbot
        │               ├── RecruitmentBotWebhookApplication.java
        │               ├── config
        │               │   ├── FacebookProperties.java
        │               │   ├── HttpClientConfig.java
        │               │   └── OpenAiProperties.java
        │               ├── controller
        │               │   └── MessengerWebhookController.java
        │               ├── dto
        │               │   ├── facebook
        │               │   │   └── FacebookSendMessageRequest.java
        │               │   └── openai
        │               │       └── OpenAiResponsesRequest.java
        │               └── service
        │                   ├── FacebookMessengerService.java
        │                   ├── MessengerWebhookService.java
        │                   ├── OpenAiService.java
        │                   └── WebhookVerificationService.java
        └── resources
            └── application.yml
```

## Configuration

Set real values in `src/main/resources/application.yml`.

```yaml
facebook:
  verify-token: your-facebook-verify-token
  page-access-token: your-facebook-page-access-token

openai:
  api-key: your-openai-api-key
```

## Run locally

1. Make sure Java 17+ and Maven are installed.
2. Update `src/main/resources/application.yml` with your Facebook and OpenAI credentials.
3. Start the app:

```bash
mvn spring-boot:run
```

4. The webhook will be available locally at:

```text
http://localhost:8080/webhook
```

## Expose local server with ngrok

1. Start the Spring Boot app.
2. In another terminal, run:

```bash
ngrok http 8080
```

3. Copy the generated HTTPS URL, for example:

```text
https://abc123.ngrok-free.app
```

4. In the Meta app webhook configuration, set:

- Callback URL: `https://abc123.ngrok-free.app/webhook`
- Verify Token: same value as `facebook.verify-token` in `application.yml`

5. Subscribe the Facebook Page to Messenger webhook events.

## Webhook behavior

### Verification request

Facebook sends:

```http
GET /webhook?hub.mode=subscribe&hub.verify_token=...&hub.challenge=...
```

If the verify token matches the configured value, the app returns the raw `hub.challenge` value.

### Message event

When a candidate sends a text message to the page:

1. Facebook calls `POST /webhook`.
2. The application logs the full payload.
3. The sender PSID and message text are extracted.
4. The candidate message is sent to OpenAI with the recruitment-assistant system prompt.
5. The generated reply is sent back through Facebook Send API.

## Notes

- Only text messages are processed in this POC.
- Echo events and non-text events are ignored.
- OpenAI response storage is disabled in the request body with `store=false`.
