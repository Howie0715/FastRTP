package me.wesley1808.fastrtp.config;

import com.google.gson.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.lang.reflect.Type;

public class Json {
    public static final Gson INSTANCE = new GsonBuilder()
            .registerTypeHierarchyAdapter(ResourceKey.class, new BiomeKeySerializer())
            .registerTypeHierarchyAdapter(TagKey.class, new BiomeTagSerializer())
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private static class BiomeTagSerializer implements JsonDeserializer<TagKey<Biome>>, JsonSerializer<TagKey<Biome>> {
        @Override
        public TagKey<Biome> deserialize(JsonElement element, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (element.isJsonPrimitive()) {
                ResourceLocation location = ResourceLocation.parse(element.getAsString());
                return TagKey.create(Registries.BIOME, location);
            }
            return null;
        }

        @Override
        public JsonElement serialize(TagKey<Biome> tag, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(tag.location().toString());
        }
    }

    private static class BiomeKeySerializer implements JsonDeserializer<ResourceKey<Biome>>, JsonSerializer<ResourceKey<Biome>> {
        public ResourceKey<Biome> deserialize(JsonElement element, Type type, JsonDeserializationContext context) throws JsonParseException {
            if (element.isJsonPrimitive()) {
                ResourceLocation location = ResourceLocation.parse(element.getAsString());
                return ResourceKey.create(Registries.BIOME, location);
            }
            return null;
        }

        public JsonElement serialize(ResourceKey<Biome> key, Type type, JsonSerializationContext context) {
            return new JsonPrimitive(key.location().toString());
        }
    }
}
