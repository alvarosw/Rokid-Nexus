<script type="application/json" def>
{"navigationBarTitleText": "Agents", "data": {"running": 2, "queued": 1}}
</script>
<page>
  <view class="root">
    <text class="title">Agents</text>
    <view class="row"><text class="label">Running</text><text class="value">{{running}}</text></view>
    <view class="row"><text class="label">Queued</text><text class="value">{{queued}}</text></view>
    <view class="row"><text class="label">Last</text><text class="value">build ok</text></view>
  </view>
</page>
<style>
.root { display: flex; flex-direction: column; padding: 16px; }
.title { font-size: 24px; font-weight: bold; margin-bottom: 12px; }
.row { display: flex; flex-direction: row; justify-content: space-between; margin-bottom: 8px; }
.label { font-size: 18px; }
.value { font-size: 18px; font-weight: bold; }
</style>
