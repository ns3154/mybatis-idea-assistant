package io.github.ns3154.mybatisassistant.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.settings.MyBatisAssistantSettings;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class MyBatisMcpToolRegistry {
    private final Project project;
    private final Map<String, MyBatisMcpTool> tools;

    MyBatisMcpToolRegistry(@NotNull Project project, @NotNull List<MyBatisMcpTool> tools) {
        this.project = project;
        Map<String, MyBatisMcpTool> byName = new LinkedHashMap<>();
        for (MyBatisMcpTool tool : tools.stream()
                .sorted(java.util.Comparator.comparing(MyBatisMcpTool::name))
                .toList()) {
            if (byName.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "mcp.error.duplicate.tool", tool.name()));
            }
        }
        this.tools = Map.copyOf(byName);
    }

    @NotNull JsonArray definitions() {
        MyBatisAssistantSettings settings = MyBatisAssistantSettings.getInstance();
        JsonArray definitions = new JsonArray();
        for (MyBatisMcpTool tool : tools.values().stream()
                .sorted(java.util.Comparator.comparing(MyBatisMcpTool::name))
                .toList()) {
            if (!isAllowed(tool, settings)) {
                continue;
            }
            JsonObject definition = new JsonObject();
            definition.addProperty("name", tool.name());
            definition.addProperty("description", tool.description());
            definition.add("inputSchema", tool.inputSchema());
            JsonObject annotations = new JsonObject();
            annotations.addProperty("readOnlyHint", tool.readOnly());
            annotations.addProperty("destructiveHint", !tool.readOnly());
            definition.add("annotations", annotations);
            definitions.add(definition);
        }
        return definitions;
    }

    @NotNull JsonObject invoke(@NotNull String name, @NotNull JsonObject arguments) {
        MyBatisMcpTool tool = tools.get(name);
        MyBatisAssistantSettings settings = MyBatisAssistantSettings.getInstance();
        if (tool == null || !isAllowed(tool, settings)) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.tool.not.allowed", name));
        }
        if (project.isDisposed() || !project.isOpen()) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.project.closed"));
        }
        validateArguments(tool.inputSchema(), arguments);
        return tool.invoke(project, arguments);
    }

    private static void validateArguments(
            @NotNull JsonObject schema,
            @NotNull JsonObject arguments) {
        JsonObject properties = schema.getAsJsonObject("properties");
        Set<String> required = schema.getAsJsonArray("required").asList().stream()
                .map(element -> element.getAsString())
                .collect(Collectors.toUnmodifiableSet());
        for (String requiredName : required) {
            if (!arguments.has(requiredName) || arguments.get(requiredName).isJsonNull()) {
                throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                        "mcp.error.argument.missing", requiredName));
            }
        }
        for (Map.Entry<String, com.google.gson.JsonElement> entry : arguments.entrySet()) {
            JsonObject property = properties.has(entry.getKey())
                    ? properties.getAsJsonObject(entry.getKey())
                    : null;
            if (property == null) {
                throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                        "mcp.error.argument.unknown", entry.getKey()));
            }
            validateValue(entry.getKey(), entry.getValue(), property);
        }
    }

    private static void validateValue(
            @NotNull String name,
            @NotNull com.google.gson.JsonElement value,
            @NotNull JsonObject property) {
        String type = property.get("type").getAsString();
        if ("string".equals(type)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                        "mcp.error.argument.string", name));
            }
            int maximum = property.has("maxLength")
                    ? property.get("maxLength").getAsInt()
                    : Integer.MAX_VALUE;
            if (value.getAsString().length() > maximum) {
                throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                        "mcp.error.argument.too.long", name));
            }
            return;
        }
        if ("integer".equals(type)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !value.getAsString().matches("-?(?:0|[1-9][0-9]*)")) {
                throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                        "mcp.error.argument.integer", name));
            }
            try {
                int integer = new java.math.BigDecimal(value.getAsString()).intValueExact();
                int minimum = property.get("minimum").getAsInt();
                int maximum = property.get("maximum").getAsInt();
                if (integer < minimum || integer > maximum) {
                    throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                            "mcp.error.argument.range", name));
                }
            } catch (ArithmeticException failure) {
                throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                        "mcp.error.argument.integer", name));
            }
            return;
        }
        throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                "mcp.error.argument.type.unsupported", name));
    }

    private static boolean isAllowed(
            @NotNull MyBatisMcpTool tool,
            @NotNull MyBatisAssistantSettings settings) {
        return settings.getMcpAllowedTools().contains(tool.name())
                && (tool.readOnly() || settings.isMcpWriteToolsEnabled());
    }
}
