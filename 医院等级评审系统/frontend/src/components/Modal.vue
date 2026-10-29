<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { X } from "lucide-vue-next";
defineProps<{ title: string; wide?: boolean; drawer?: boolean }>();
const emit = defineEmits<{ close: [] }>();
const panel = ref<HTMLElement>();
const previous = document.activeElement as HTMLElement | null;
function keydown(event: KeyboardEvent) {
  if (event.key === "Escape") emit("close");
  if (event.key !== "Tab" || !panel.value) return;
  const nodes = [
    ...panel.value.querySelectorAll<HTMLElement>(
      'button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href],[tabindex="0"]',
    ),
  ].filter((x) => x.offsetParent !== null);
  const first = nodes[0],
    last = nodes[nodes.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last?.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first?.focus();
  }
}
onMounted(async () => {
  document.addEventListener("keydown", keydown);
  await nextTick();
  panel.value?.focus();
  document.body.style.overflow = "hidden";
});
onBeforeUnmount(() => {
  document.removeEventListener("keydown", keydown);
  document.body.style.overflow = "";
  previous?.focus();
});
</script>
<template>
  <Teleport to="body"
    ><div
      class="modal-backdrop"
      :class="{ 'is-drawer': drawer }"
      @mousedown.self="emit('close')"
    >
      <section
        ref="panel"
        class="modal"
        :class="{ wide, drawer }"
        role="dialog"
        aria-modal="true"
        :aria-label="title"
        tabindex="-1"
      >
        <header class="modal-header">
          <div>
            <span class="eyebrow">医评智控 · 演示工作区</span>
            <h2>{{ title }}</h2>
          </div>
          <button
            class="icon-button"
            aria-label="关闭弹层"
            @click="emit('close')"
          >
            <X :size="20" />
          </button>
        </header>
        <div class="modal-body"><slot /></div>
      </section></div
  ></Teleport>
</template>
