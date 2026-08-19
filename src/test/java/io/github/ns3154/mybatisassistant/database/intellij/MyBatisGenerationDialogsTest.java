package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.generator.MyBatisGeneratedArtifact;
import io.github.ns3154.mybatisassistant.generator.MyBatisGeneratedRegion;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationArtifactKind;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfigurationCodec;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlan;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanEntry;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanStatus;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationTemplateGroup;
import io.github.ns3154.mybatisassistant.generator.MyBatisSafeMergeConflictCode;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class MyBatisGenerationDialogsTest extends BasePlatformTestCase {
    public void testOptionsDialogImportsAndExportsDeterministicConfiguration() {
        MyBatisGenerationOptionsDialog dialog =
                new MyBatisGenerationOptionsDialog(getProject());
        try {
            assertEquals(MyBatisGenerationConfiguration.standard("com.example"),
                    dialog.configuration());
            MyBatisGenerationConfiguration expected = new MyBatisGenerationConfiguration(
                    "com.example.generated",
                    "modules/app/src/main/java",
                    "modules/app/src/main/resources",
                    EnumSet.of(
                            MyBatisGenerationArtifactKind.ENTITY,
                            MyBatisGenerationArtifactKind.XML),
                    MyBatisGenerationTemplateGroup.MYBATIS_PLUS,
                    "t_",
                    "Entity",
                    false,
                    true,
                    Set.of("deleted_at"),
                    Map.of());

            dialog.applyConfigurationText(
                    MyBatisGenerationConfigurationCodec.encode(expected));

            assertEquals(expected, dialog.configuration());
            assertEquals(MyBatisGenerationConfigurationCodec.encode(expected),
                    dialog.exportConfigurationText());
        } finally {
            dialog.disposeIfNeeded();
        }
    }

    public void testPreviewDialogSelectsOnlyChangesAndBlocksConflicts() {
        MyBatisGeneratedArtifact createArtifact = artifact("Create.java", "create");
        MyBatisGeneratedArtifact updateArtifact = artifact("Update.java", "update");
        MyBatisGeneratedArtifact unchangedArtifact = artifact("Same.java", "same");
        MyBatisGenerationPlan plan = new MyBatisGenerationPlan(List.of(
                ready(createArtifact, MyBatisGenerationPlanStatus.CREATE,
                        Optional.empty(), createArtifact.content()),
                ready(updateArtifact, MyBatisGenerationPlanStatus.UPDATE,
                        Optional.of("旧文本"), updateArtifact.content()),
                ready(unchangedArtifact, MyBatisGenerationPlanStatus.UNCHANGED,
                        Optional.of(unchangedArtifact.content()), unchangedArtifact.content())));
        MyBatisGenerationPreviewDialog dialog =
                new MyBatisGenerationPreviewDialog(getProject(), plan);
        try {
            assertTrue(dialog.isOKActionEnabled());
            assertEquals(Set.of(
                            createArtifact.relativePath(), updateArtifact.relativePath()),
                    dialog.selectedPlan().entries().stream()
                            .map(entry -> entry.artifact().relativePath())
                            .collect(java.util.stream.Collectors.toSet()));
            assertTrue(dialog.previewText().contains("mybatis-assistant-generated"));

            dialog.setPathSelected(updateArtifact.relativePath(), false);

            assertEquals(List.of(createArtifact.relativePath()),
                    dialog.selectedPlan().entries().stream()
                            .map(entry -> entry.artifact().relativePath())
                            .toList());
            assertThrows(IllegalArgumentException.class, () -> dialog.setPathSelected(
                    unchangedArtifact.relativePath(), true));
        } finally {
            dialog.disposeIfNeeded();
        }

        MyBatisGeneratedArtifact conflictArtifact = artifact("Conflict.java", "conflict");
        MyBatisGenerationPlan conflictPlan = new MyBatisGenerationPlan(List.of(
                new MyBatisGenerationPlanEntry(
                        conflictArtifact,
                        MyBatisGenerationPlanStatus.CONFLICT,
                        Optional.of("用户文件"),
                        Optional.empty(),
                        Optional.of(MyBatisSafeMergeConflictCode.GENERATED_REGION_MODIFIED),
                        Optional.of("生成区已被修改"))));
        MyBatisGenerationPreviewDialog conflictDialog =
                new MyBatisGenerationPreviewDialog(getProject(), conflictPlan);
        try {
            assertFalse(conflictDialog.isOKActionEnabled());
            assertFalse(conflictDialog.selectedPlan().hasChanges());
            assertEquals("生成区已被修改", conflictDialog.previewText());
        } finally {
            conflictDialog.disposeIfNeeded();
        }
    }

    private static MyBatisGeneratedArtifact artifact(String fileName, String id) {
        String regionId = "dialog:" + id;
        return new MyBatisGeneratedArtifact(
                MyBatisGenerationArtifactKind.ENTITY,
                "src/main/java/com/example/" + fileName,
                MyBatisGeneratedRegion.render(
                        MyBatisGeneratedRegion.Style.JAVA,
                        regionId,
                        "package com.example; public class "
                                + fileName.substring(0, fileName.length() - 5) + " {}\n"),
                Set.of(regionId));
    }

    private static MyBatisGenerationPlanEntry ready(
            MyBatisGeneratedArtifact artifact,
            MyBatisGenerationPlanStatus status,
            Optional<String> existing,
            String proposed) {
        return new MyBatisGenerationPlanEntry(
                artifact,
                status,
                existing,
                Optional.of(proposed),
                Optional.empty(),
                Optional.empty());
    }
}
