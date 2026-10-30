// 配送域接口：出库单卡片提交、配送管理（管理员）、我的配送单（配送员）
import { getJson, authFetch, postJson } from './http';

/** 出库单卡片：提交执行 */
export const outboundSubmit = (form) => authFetch('/api/form/outbound/submit', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(form)
}).then(r => r.json());

/** 配送单卡片：提交执行（必要时先建订单，再建配送单并拣货出库） */
export const deliveryFormSubmit = (form) => authFetch('/api/form/delivery/submit', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(form)
}).then(r => r.json());

/** 配送管理（管理员） */
export const deliveryList = (status) => getJson('/api/delivery' + (status ? '?status=' + encodeURIComponent(status) : ''));
export const deliveryDetail = (deliveryNo) => getJson('/api/delivery/' + encodeURIComponent(deliveryNo));
export const deliveryCheckStock = (warehouseId, itemId, quantity) =>
  getJson('/api/delivery/check-stock?warehouseId=' + warehouseId + '&itemId=' + itemId + '&quantity=' + quantity);
export const deliveryCreate = (form) => postJson('/api/delivery', form);
export const deliveryConfirmOutbound = (deliveryNo) => postJson('/api/delivery/' + encodeURIComponent(deliveryNo) + '/confirm-outbound');
export const deliveryCancel = (deliveryNo) => postJson('/api/delivery/' + encodeURIComponent(deliveryNo) + '/cancel');

/** 我的配送单（配送员） */
export const deliveryMyList = (status) => getJson('/api/delivery/my/list' + (status ? '?status=' + encodeURIComponent(status) : ''));
export const deliveryMyDetail = (deliveryNo) => getJson('/api/delivery/my/' + encodeURIComponent(deliveryNo));
export const deliveryMyDelivered = (deliveryNo) => postJson('/api/delivery/my/' + encodeURIComponent(deliveryNo) + '/delivered');
