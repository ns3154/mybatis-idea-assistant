package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record MyBatisProjectConfigurationModel(
        @NotNull List<String> configLocations,
        @NotNull List<String> mapperLocations,
        @NotNull List<String> mapperResources,
        @NotNull List<String> mapperUrls,
        @NotNull List<String> mapperClasses,
        @NotNull List<String> mapperPackages,
        @NotNull List<String> typeAliasPackages,
        @NotNull List<String> typeHandlerPackages) {
    public MyBatisProjectConfigurationModel {
        configLocations = List.copyOf(configLocations);
        mapperLocations = List.copyOf(mapperLocations);
        mapperResources = List.copyOf(mapperResources);
        mapperUrls = List.copyOf(mapperUrls);
        mapperClasses = List.copyOf(mapperClasses);
        mapperPackages = List.copyOf(mapperPackages);
        typeAliasPackages = List.copyOf(typeAliasPackages);
        typeHandlerPackages = List.copyOf(typeHandlerPackages);
    }
}
