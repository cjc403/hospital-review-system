<script setup lang="ts">
import { label } from "../domain";
defineProps<{ value: string | null; text?: string }>();
</script>
<template>
  <span class="badge" :class="`badge-${value}`"
    ><span class="badge-dot"></span>{{ text || label(value) }}</span
  >
</template>
