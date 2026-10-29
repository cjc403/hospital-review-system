<script setup lang="ts">
import { computed } from "vue";
import { FileText, Upload, Download } from "lucide-vue-next";
import { busy, canWrite, download, uploadEvidence, workspace } from "../store";
import { formatDate } from "../domain";
const props = defineProps<{
  ownerType: "prereq" | "improvement";
  ownerId: string;
  readOnly?: boolean;
}>();
const files = computed(
  () =>
    workspace.value?.evidenceFiles.filter(
      (x) => x.ownerType === props.ownerType && x.ownerId === props.ownerId,
    ) || [],
);
async function upload(event: Event) {
  const input = event.target as HTMLInputElement;
  if (input.files?.[0])
    await uploadEvidence(input.files[0], props.ownerType, props.ownerId);
  input.value = "";
}
</script>
<template>
  <div class="evidence-panel">
    <div class="section-heading">
      <h3>
        佐证材料 <span class="count">{{ files.length }}</span>
      </h3>
      <label
        v-if="canWrite && !readOnly"
        class="button button-small upload-label"
        :class="{ disabled: busy }"
        ><Upload :size="14" />上传佐证<input
          type="file"
          accept=".pdf,.png,.jpg,.jpeg,.txt,.docx"
          :disabled="busy"
          @change="upload"
      /></label>
    </div>
    <p class="hint">
      支持 PDF / 图片 / TXT / DOCX，每份不超过 5
      MiB。文件将真实保存并绑定本条记录。
    </p>
    <div v-for="file in files" :key="file.id" class="file-row">
      <FileText :size="18" />
      <div class="grow">
        <strong>{{ file.fileName }}</strong
        ><small
          >{{ (file.size / 1024).toFixed(1) }} KB · v{{ file.version }} ·
          {{ formatDate(file.createdAt) }}</small
        >
      </div>
      <button
        type="button"
        class="icon-button"
        :disabled="busy"
        aria-label="下载佐证"
        @click="download(`/evidence/${file.id}/download`, file.fileName)"
      >
        <Download :size="16" />
      </button>
    </div>
    <div v-if="!files.length" class="empty small">暂无已上传材料</div>
  </div>
</template>
