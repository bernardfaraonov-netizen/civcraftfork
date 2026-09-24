package com.civcraft.storage;

import com.civcraft.core.util.BlockPos;
import com.civcraft.core.util.ChunkKey;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.function.Function;

/** Shared Gson instance used for persisting domain documents. */
public final class Json {

    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .enableComplexMapKeySerialization()
            .registerTypeAdapter(ChunkKey.class, stringAdapter(ChunkKey::toString, ChunkKey::parse))
            .registerTypeAdapter(BlockPos.class, stringAdapter(BlockPos::toString, BlockPos::parse))
            .registerTypeAdapter(Instant.class, stringAdapter(Instant::toString, Instant::parse))
            .create();

    private Json() {
    }

    private static <T> TypeAdapter<T> stringAdapter(Function<T, String> writer, Function<String, T> reader) {
        return new TypeAdapter<T>() {
            @Override
            public void write(JsonWriter out, T value) throws IOException {
                if (value == null) out.nullValue();
                else out.value(writer.apply(value));
            }

            @Override
            public T read(JsonReader in) throws IOException {
                if (in.peek() == JsonToken.NULL) {
                    in.nextNull();
                    return null;
                }
                return reader.apply(in.nextString());
            }
        }.nullSafe();
    }
}
