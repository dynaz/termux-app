<div class="card">
  <div class="card-body">
    <h3 class="card-title">Link TermiNow</h3>
    {if $code}
      <p>Enter this code in TermiNow, in Settings → Account → Link existing account. It works once, for {$seconds/60} minutes.</p>
      <p style="font: bold 34px/1.4 monospace; letter-spacing: 6px; padding: 14px; border: 1px solid #ccc; border-radius: 10px; text-align: center;">{$code|escape}</p>
    {else}
      <p>This gives you a one-time code that links this client account to your TermiNow account.</p>
      <form method="post" action="index.php?m=terminow_play">
        <input type="hidden" name="token" value="{$token}">
        <input type="hidden" name="action" value="code">
        <button type="submit" class="btn btn-primary">Get a code</button>
      </form>
    {/if}
  </div>
</div>
