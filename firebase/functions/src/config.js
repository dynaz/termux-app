'use strict';

const { TermiNowError } = require('./errors');

/**
 * The configuration of the functions, from environment variables that Firebase sets from the
 * params in index.js (functions/.env.<project> or prompted at deploy). Nothing here is secret
 * except WHMCS_SYNC_SECRET, which comes from Secret Manager.
 *
 * PLAY_PRODUCT_MAP is the server-owned mapping of what may be bought:
 *   {"<Play product id>": {"<base plan id>": {"tier": "premium", "whmcsProductId": 12}}}
 * A product or base plan that is not in it is rejected.
 */
function load(env = process.env) {
  const problems = [];
  const packageName = (env.PLAY_PACKAGE_NAME || '').trim();
  if (!/^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/.test(packageName))
    problems.push({ code: 'CONFIG_PLAY_PACKAGE', message: 'PLAY_PACKAGE_NAME is missing or not a package name' });

  let productMap = {};
  try {
    productMap = JSON.parse(env.PLAY_PRODUCT_MAP || '');
    validateProductMap(productMap);
  } catch (e) {
    problems.push({ code: 'CONFIG_PRODUCT_MAP', message: 'PLAY_PRODUCT_MAP is missing or invalid: ' + (e.code ? e.message : 'not JSON') });
    productMap = {};
  }

  const whmcsUrl = (env.WHMCS_SYNC_URL || '').trim();
  if (whmcsUrl && !/^https:\/\/[^\s/]+\/\S*$/.test(whmcsUrl))
    problems.push({ code: 'CONFIG_WHMCS_URL', message: 'WHMCS_SYNC_URL must be an https URL' });
  const whmcsSecret = env.WHMCS_SYNC_SECRET || '';
  if (whmcsUrl && whmcsSecret.length < 32)
    problems.push({ code: 'CONFIG_WHMCS_SECRET', message: 'The secret WHMCS_SYNC_SECRET must be set, at least 32 characters' });

  return {
    packageName,
    productMap,
    whmcsUrl,
    whmcsSecret,
    problems,
  };
}

function validateProductMap(map) {
  if (!map || typeof map !== 'object' || Array.isArray(map) || Object.keys(map).length === 0)
    throw new TermiNowError('CONFIG_PRODUCT_MAP', 'it must map at least one product');
  for (const [productId, plans] of Object.entries(map)) {
    if (!/^[a-z0-9][a-z0-9._]{0,99}$/.test(productId)) throw new TermiNowError('CONFIG_PRODUCT_MAP', `invalid product id ${productId}`);
    if (!plans || typeof plans !== 'object' || Object.keys(plans).length === 0)
      throw new TermiNowError('CONFIG_PRODUCT_MAP', `${productId} has no base plans`);
    for (const [basePlanId, plan] of Object.entries(plans)) {
      if (!/^[a-z0-9][a-z0-9-]{0,62}$/.test(basePlanId)) throw new TermiNowError('CONFIG_PRODUCT_MAP', `invalid base plan id ${basePlanId}`);
      if (!plan || typeof plan.tier !== 'string' || !/^[a-z][a-z0-9_]{0,31}$/.test(plan.tier))
        throw new TermiNowError('CONFIG_PRODUCT_MAP', `${productId}/${basePlanId} needs a tier`);
      if (plan.whmcsProductId !== undefined && !(Number.isInteger(plan.whmcsProductId) && plan.whmcsProductId > 0))
        throw new TermiNowError('CONFIG_PRODUCT_MAP', `${productId}/${basePlanId} has an invalid whmcsProductId`);
    }
  }
}

/** Throw a sanitized error if the configuration can not be used for Play verification. */
function requireUsable(config) {
  const blocking = config.problems.filter((p) => p.code === 'CONFIG_PLAY_PACKAGE' || p.code === 'CONFIG_PRODUCT_MAP');
  if (blocking.length > 0)
    throw new TermiNowError('BACKEND_NOT_CONFIGURED', 'Google Play purchases are not configured on the server (' +
      blocking.map((p) => p.code).join(', ') + ')');
}

module.exports = { load, validateProductMap, requireUsable };
