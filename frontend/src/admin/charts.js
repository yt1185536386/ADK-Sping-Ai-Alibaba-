// ECharts 封装：按需注册图表与组件，避免整包引入拖慢首屏。
import * as echarts from 'echarts/core';
import { BarChart, GaugeChart, LineChart, PieChart } from 'echarts/charts';
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { markRaw, onBeforeUnmount, onMounted, shallowRef, watch } from 'vue';

echarts.use([
  LineChart, BarChart, PieChart, GaugeChart,
  GridComponent, TooltipComponent, LegendComponent, TitleComponent, DataZoomComponent,
  CanvasRenderer
]);

export { echarts };

/** 统一的图表配色（与 Element Plus 主色系保持一致） */
export const CHART_COLORS = ['#4f6ef7', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#64748b'];

/**
 * 创建一个跟随容器尺寸变化的 ECharts 实例。
 *
 * 注意：实例必须用 shallowRef + markRaw 持有——普通 ref 会把 echarts 实例包成
 * 深度响应式 Proxy，破坏其内部的 instanceof / Map 键 / DOM 引用判断，
 * 典型症状就是「饼图能出、柱线图系列不渲染、悬浮无数据」。
 *
 * @param {import('vue').Ref<HTMLElement|null>} elRef 容器 ref
 * @param {object} option 图表配置
 * @param {Function} [onReady] 实例创建后的回调（用于 setOption）
 */
export function useChart(elRef, option, onReady) {
  const chart = shallowRef(null);

  function render() {
    if (!elRef.value) return;
    if (!chart.value) {
      chart.value = markRaw(echarts.init(elRef.value));
      if (onReady) onReady(chart.value);
    }
    // 数据晚于挂载到达（异步加载）时容器可能刚完成布局，先 resize 再 setOption
    chart.value.resize();
    chart.value.setOption(option.value || option, true);
  }

  function resize() {
    if (chart.value) chart.value.resize();
  }

  onMounted(() => {
    render();
    window.addEventListener('resize', resize);
  });

  onBeforeUnmount(() => {
    window.removeEventListener('resize', resize);
    if (chart.value) {
      chart.value.dispose();
      chart.value = null;
    }
  });

  // 配置变化时重绘（option 传 ref/computed 时自动跟随）
  if (option && typeof option === 'object' && 'value' in option) {
    watch(option, () => render(), { deep: true });
  }

  return { chart, render, resize };
}

/** 统一的 tooltip / 网格 / 坐标轴样式 */
export const baseOption = () => ({
  color: CHART_COLORS,
  tooltip: { trigger: 'axis', confine: true },
  grid: { left: 12, right: 16, top: 40, bottom: 8, containLabel: true },
  legend: { top: 4, icon: 'roundRect', itemWidth: 12, itemHeight: 8 }
});
