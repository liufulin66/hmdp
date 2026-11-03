(function (global) {
  "use strict";
  const contexts = new WeakMap();
  function state(vm) {
    let value = contexts.get(vm);
    if (!value) { value = {generation: 0, request: 0, fingerprint: '', next: 1, replace: true, refreshing: false}; contexts.set(vm, value); }
    return value;
  }
  function fingerprint(vm) { const p = vm.params; return JSON.stringify([p.typeId, p.x, p.y, p.radius]); }
  async function load(vm, reset = false) {
    const value = state(vm), signature = fingerprint(vm), changed = signature !== value.fingerprint;
    if (vm.pending && !changed && (!reset || value.refreshing)) return false;
    if (!changed && !reset && !vm.hasMore && !value.replace) return false;
    if (changed || reset) {
      value.generation++; value.fingerprint = signature; value.next = 1; value.replace = true;
      vm.hasMore = true;
      if (changed) { vm.shops = []; vm.loaded = false; }
    }
    const generation = value.generation, request = ++value.request, page = value.next, replace = value.replace;
    const active = () => contexts.get(vm) === value && value.generation === generation && value.request === request;
    value.refreshing = replace; vm.pending = true; vm.error = '';
    const params = {...vm.params, current: page, pageInfo: true};
    if (params.x == null && params.y == null) { delete params.x; delete params.y; }
    try {
      const {data} = await axios.get('/shop/of/type', {params, headers: {'Cache-Control': 'no-cache'}});
      if (!active()) return false;
      if (!data || !Array.isArray(data.list) || data.list.length > 5 || typeof data.hasMore !== 'boolean') {
        throw new Error('商铺分页返回异常，请重试');
      }
      const rows = data.list.map(shop => ({...shop, img: String(shop.images || '').split(',')[0]}));
      const seen = new Set((replace ? [] : vm.shops).map(shop => String(shop.id)));
      const unique = rows.filter(shop => { const id = String(shop.id); if (seen.has(id)) return false; seen.add(id); return true; });
      vm.shops = replace ? unique : vm.shops.concat(unique);
      vm.params.current = page; vm.hasMore = data.hasMore && page < 20; vm.loaded = true;
      value.next = page + 1; value.replace = false;
      return true;
    } catch (error) {
      if (active()) { vm.error = String(error && error.message || error || '加载失败，请重试'); vm.$message.error(vm.error); }
      return false;
    } finally { if (active()) { vm.pending = false; value.refreshing = false; } }
  }
  function coordinates(vm, mode, x, y) {
    if (mode === 'classification') {
      vm.params.x = null; vm.params.y = null; vm.locationMode = mode;
    } else {
      if (String(x).trim() === '' || String(y).trim() === '') { vm.$message.error('请同时输入经度和纬度'); return false; }
      x = Number(x); y = Number(y);
      if (!Number.isFinite(x) || !Number.isFinite(y) || x < -180 || x > 180 || y < -85.05112878 || y > 85.05112878) {
        vm.$message.error('请输入Redis GEO范围内的有效经纬度'); return false;
      }
      vm.params.x = x; vm.params.y = y; vm.locationMode = mode;
    }
    return load(vm, true);
  }
  function distance(value) {
    if (value == null || !Number.isFinite(Number(value))) return '';
    return value < 1000 ? Number(value).toFixed(1) + ' m' : (value / 1000).toFixed(1) + ' km';
  }
  global.shopNearby = {load, coordinates, distance, destroy: vm => contexts.delete(vm)};
})(window);
