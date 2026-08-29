package im.conversations.android.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import eu.siacs.conversations.xmpp.Jid;
import java.io.IOException;
import okhttp3.HttpUrl;

public class Services {

    public static final Gson GSON;

    static {
        GSON =
                new GsonBuilder()
                        .registerTypeAdapter(Jid.class, new JidTypeAdapter())
                        .registerTypeAdapter(HttpUrl.class, new HttpUrlTypeAdapter())
                        .create();
    }

    private static class HttpUrlTypeAdapter extends TypeAdapter<HttpUrl> {

        @Override
        public void write(final JsonWriter out, final HttpUrl value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public HttpUrl read(final JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            } else if (in.peek() == JsonToken.STRING) {
                final String value = in.nextString();
                return HttpUrl.parse(value);
            }
            throw new IOException("Unexpected token");
        }
    }

    private static class JidTypeAdapter extends TypeAdapter<Jid> {
        @Override
        public void write(final JsonWriter out, final Jid value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Jid read(final JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            } else if (in.peek() == JsonToken.STRING) {
                final String value = in.nextString();
                return Jid.of(value);
            }
            throw new IOException("Unexpected token");
        }
    }
}
