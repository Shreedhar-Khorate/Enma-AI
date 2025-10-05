package com.example.bot;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private static final int MAX_HISTORY = 5; // Keep last 5 exchanges
    private RecyclerView chatRecyclerView;
    private ChatAdapter chatAdapter;
    private EditText messageInput;
    private ImageButton sendButton, micButton;
    private ProgressBar progressBar;
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private boolean isListening = false;
    private List<String> userHistory = new ArrayList<>();
    private List<String> responseHistory = new ArrayList<>();

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final String API_KEY = "";
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent";

    private static class ChatMessage {
        String message;
        boolean isUser;

        ChatMessage(String message, boolean isUser) {
            this.message = message;
            this.isUser = isUser;
        }
    }

    private class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.MessageViewHolder> {
        private final List<ChatMessage> messages = new ArrayList<>();

        void addMessage(ChatMessage message) {
            messages.add(message);
            notifyItemInserted(messages.size() - 1);
        }

        @Override
        public MessageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(
                    viewType == 0 ? R.layout.item_message_user : R.layout.item_message_bot,
                    parent, false);
            return new MessageViewHolder(view);
        }

        @Override
        public void onBindViewHolder(MessageViewHolder holder, int position) {
            holder.messageText.setText(messages.get(position).message);
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        @Override
        public int getItemViewType(int position) {
            return messages.get(position).isUser ? 0 : 1;
        }

        class MessageViewHolder extends RecyclerView.ViewHolder {
            TextView messageText;

            MessageViewHolder(View itemView) {
                super(itemView);
                messageText = itemView.findViewById(R.id.messageText);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize views
        chatRecyclerView = findViewById(R.id.chatRecyclerView);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        micButton = findViewById(R.id.micButton);
        progressBar = findViewById(R.id.progressBar);

        // Setup RecyclerView
        chatAdapter = new ChatAdapter();
        chatRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        chatRecyclerView.setAdapter(chatAdapter);

        // Initialize TextToSpeech
        textToSpeech = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.ERROR) {
                textToSpeech.setLanguage(Locale.getDefault());
            }
        });

        // Check and request microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO_PERMISSION);
        } else {
            setupSpeechRecognizer();
        }

        // Set click listeners
        sendButton.setOnClickListener(v -> sendMessage());
        micButton.setOnClickListener(v -> toggleSpeechInput());

        messageInput.setOnEditorActionListener((v, actionId, event) -> {
            sendMessage();
            return true;
        });

        // Add welcome message
        addMessage("Hello! I'm Enma AI, your personal assistant. How can I help you today?", false);
        speak("Hello! I'm Enma AI, your personal assistant. How can I help you today?");
    }

    private void setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                runOnUiThread(() -> {
                    micButton.setColorFilter(ContextCompat.getColor(MainActivity.this, android.R.color.holo_red_light));
                    Toast.makeText(MainActivity.this, "Listening...", Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                runOnUiThread(() -> {
                    micButton.setColorFilter(null);
                    isListening = false;
                });
            }
            @Override public void onError(int error) {
                runOnUiThread(() -> {
                    micButton.setColorFilter(null);
                    isListening = false;
                    Toast.makeText(MainActivity.this, "Error: " + error, Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String text = matches.get(0);
                    runOnUiThread(() -> {
                        messageInput.setText(text);
                        sendMessage();
                    });
                }
            }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void toggleSpeechInput() {
        if (!isListening) {
            if (speechRecognizer == null) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    setupSpeechRecognizer();
                } else {
                    Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...");

            try {
                speechRecognizer.startListening(intent);
                isListening = true;
            } catch (Exception e) {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            speechRecognizer.stopListening();
            isListening = false;
            micButton.setColorFilter(null);
        }
    }

    private void speak(String text) {
        if (textToSpeech != null) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    private void sendMessage() {
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) return;

        addMessage(message, true);
        messageInput.setText("");
        progressBar.setVisibility(View.VISIBLE);
        callGeminiAPI(message);
    }

    private String buildPrompt(String userInput) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("You are Enma AI, an advanced AI assistant developed by the Enma Team. ");
        promptBuilder.append("Your responses should be helpful, accurate, and concise. ");
        promptBuilder.append("Current conversation context:\n");

        if (!userHistory.isEmpty() && !responseHistory.isEmpty()) {
            int exchanges = Math.min(userHistory.size(), responseHistory.size());
            int start = Math.max(0, exchanges - MAX_HISTORY);

            for (int i = start; i < exchanges; i++) {
                promptBuilder.append("User: ").append(userHistory.get(i)).append("\n");
                promptBuilder.append("Assistant: ").append(responseHistory.get(i)).append("\n");
            }
        }

        promptBuilder.append("Current question: ").append(userInput).append("\n");
        promptBuilder.append("Please provide a clear and helpful response.");

        return promptBuilder.toString();
    }

    private void callGeminiAPI(String message) {
        // Add to user history (trim if too long)
        if (userHistory.size() >= MAX_HISTORY) {
            userHistory.remove(0);
            responseHistory.remove(0);
        }
        userHistory.add(message);

        try {
            String prompt = buildPrompt(message);

            JSONObject requestBody = new JSONObject()
                    .put("contents", new JSONArray().put(
                            new JSONObject().put("parts", new JSONArray().put(
                                    new JSONObject().put("text", prompt)
                            ))
                    ))
                    .put("generationConfig", new JSONObject()
                            .put("temperature", 0.7)
                            .put("topP", 0.9)
                            .put("maxOutputTokens", 1000)
                    );

            Request request = new Request.Builder()
                    .url(API_URL + "?key=" + API_KEY)
                    .post(RequestBody.create(
                            requestBody.toString(),
                            MediaType.get("application/json; charset=utf-8")))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        addMessage("Error: " + e.getMessage(), false);
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    runOnUiThread(() -> progressBar.setVisibility(View.GONE));

                    try (ResponseBody responseBody = response.body()) {
                        if (!response.isSuccessful()) {
                            String error = responseBody != null ? responseBody.string() : "Empty error response";
                            runOnUiThread(() -> addMessage("API Error " + response.code() + ": " + error, false));
                            return;
                        }

                        String jsonResponse = responseBody.string();
                        String result = parseResponse(jsonResponse);

                        // Add to response history
                        if (responseHistory.size() >= MAX_HISTORY) {
                            responseHistory.remove(0);
                        }
                        responseHistory.add(result);

                        runOnUiThread(() -> {
                            addMessage(result, false);
                            speak(result);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> addMessage("Processing error: " + e.getMessage(), false));
                    }
                }
            });
        } catch (JSONException e) {
            progressBar.setVisibility(View.GONE);
            addMessage("Request creation error: " + e.getMessage(), false);
        }
    }

    private String parseResponse(String jsonResponse) {
        try {
            JSONObject json = new JSONObject(jsonResponse);
            if (!json.has("candidates")) {
                return "Unexpected response format";
            }

            JSONArray candidates = json.getJSONArray("candidates");
            if (candidates.length() == 0) {
                return "No response was generated";
            }

            return candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text");
        } catch (JSONException e) {
            return "Response parsing error: " + e.getMessage();
        }
    }

    private void addMessage(String message, boolean isUser) {
        chatAdapter.addMessage(new ChatMessage(message, isUser));
        chatRecyclerView.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupSpeechRecognizer();
            } else {
                Toast.makeText(this, "Permission denied to record audio", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }
}