const queryString = window.location.search;
const params = new URLSearchParams(queryString);
const initialDarkTheme = params.get('darkTheme') === 'true';
const initialMapStyleUrl = params.get('mapStyleUrl') || 'mapbox://styles/mapbox/streets-v11';
const initialMapLanguage = params.get('language');
let mapAttributionPosition = params.get('mapAttributionPosition') === 'top-start'
  ? 'top-start'
  : 'bottom-end';
let isRtl = params.get('rtl') === 'true';
const mapAttributionLabels = {
  terms: params.get('mapAttributionTermsLabel') || 'Terms',
  improve: params.get('mapAttributionImproveLabel') || 'Improve this map',
  show: params.get('mapAttributionShowLabel') || 'Show map attribution',
  hide: params.get('mapAttributionHideLabel') || 'Hide map attribution',
};

document.documentElement.dir = isRtl ? 'rtl' : 'ltr';
document.documentElement.lang = initialMapLanguage || 'en';
document.documentElement.dataset.mapAttributionPosition = mapAttributionPosition;

mapboxgl.accessToken = 'REDACTED_MAPBOX_TOKEN';

function applyDocumentTheme(darkTheme) {
  document.documentElement.dataset.theme = darkTheme ? 'dark' : 'light';
}

applyDocumentTheme(initialDarkTheme);

const defaultPadding = 25;
const initialBottomPadding = parseFloat(params.get('bottomPadding')) || 0;
const initialControlsBottomPadding = parseFloat(params.get('controlsBottomPadding')) || 0;
const initialFocusVerticalFraction = parseFloat(params.get('focusVerticalFraction'));
const refitBoundsOnResize = params.get('refitBoundsOnResize') === 'true';

function setMapControlsBottomPadding(bottomPadding) {
  const normalizedBottomPadding = Number.isFinite(bottomPadding) ? Math.max(0, bottomPadding) : 0;
  document.documentElement.style.setProperty('--map-controls-bottom-padding', `${normalizedBottomPadding}px`);
}

setMapControlsBottomPadding(initialControlsBottomPadding);

const basePadding = {
  top: defaultPadding,
  bottom: defaultPadding + initialBottomPadding,
  left: defaultPadding,
  right: defaultPadding,
};
const mapConfig = {
  container: 'map',
  style: initialMapStyleUrl,
  language: initialMapLanguage,
  interactive: params.get('interactive') === 'true',
  padding: basePadding,
  attributionControl: false,
};

// If the location provided, set it as the map center
const latitude = params.get('latitude');
const longitude = params.get('longitude');
if (latitude && longitude) {
  mapConfig.center = [longitude, latitude];
  mapConfig.zoom = parseFloat(params.get('zoom'));
}

const map = new mapboxgl.Map(mapConfig);

const mapboxLogo = document.querySelector('.mapboxgl-ctrl-logo');
mapboxLogo?.removeAttribute('href');
mapboxLogo?.removeAttribute('target');
mapboxLogo?.setAttribute('aria-disabled', 'true');
mapboxLogo?.setAttribute('tabindex', '-1');

const mapAttributionIcons = `
  <svg class="map-attribution-icon info" viewBox="0 0 20 20" aria-hidden="true">
    <circle cx="10" cy="10" r="6"></circle>
    <circle cx="10" cy="7" r="0.75" fill="currentColor" stroke="none"></circle>
    <path d="M10 10v3"></path>
  </svg>
  <svg class="map-attribution-icon close" viewBox="0 0 20 20" aria-hidden="true">
    <path d="M5.5 5.5l9 9M14.5 5.5l-9 9"></path>
  </svg>
`;

function attributionControlPosition() {
  if (mapAttributionPosition === 'top-start') {
    return isRtl ? 'top-right' : 'top-left';
  }
  return isRtl ? 'bottom-left' : 'bottom-right';
}

let attributionControl;
let attributionMutationObserver;
let attributionUi;

function updateAttributionContent() {
  if (!attributionUi) {
    return;
  }
  const { links } = attributionUi;
  const mapboxLink = Array.from(links.querySelectorAll('a')).find(link =>
    link.href.startsWith('https://www.mapbox.com/about/maps'),
  );
  let termsLink = links.querySelector('.map-attribution-terms');
  if (mapboxLink && !termsLink) {
    termsLink = document.createElement('a');
    termsLink.className = 'map-attribution-terms';
    termsLink.href = 'https://www.mapbox.com/legal/end-user-terms';
    termsLink.target = '_blank';
    termsLink.rel = 'noopener noreferrer';
    mapboxLink.after(document.createTextNode(' '), termsLink);
  }
  if (termsLink && termsLink.textContent !== mapAttributionLabels.terms) {
    termsLink.textContent = mapAttributionLabels.terms;
  }

  const improveLink = links.querySelector('.mapbox-improve-map');
  if (improveLink && improveLink.textContent !== mapAttributionLabels.improve) {
    improveLink.textContent = mapAttributionLabels.improve;
  }
}

function updateAttributionToggle() {
  if (!attributionUi) {
    return;
  }
  const { control, toggle } = attributionUi;
  const expanded = control.classList.contains('mapboxgl-compact-show');
  toggle.setAttribute('aria-expanded', expanded.toString());
  toggle.setAttribute('aria-label', expanded ? mapAttributionLabels.hide : mapAttributionLabels.show);
}

function bindMapAttributionControl() {
  const control = document.querySelector('.mapboxgl-ctrl-attrib');
  const toggle = control?.querySelector('.mapboxgl-ctrl-attrib-button');
  const links = control?.querySelector('.mapboxgl-ctrl-attrib-inner');
  if (!control || !toggle || !links) {
    attributionUi = undefined;
    return;
  }

  attributionUi = { control, toggle, links };
  toggle.innerHTML = mapAttributionIcons;
  updateAttributionToggle();
  attributionMutationObserver?.disconnect();
  attributionMutationObserver = new MutationObserver(updateAttributionContent);
  attributionMutationObserver.observe(links, { childList: true, subtree: true });
  updateAttributionContent();

  control.addEventListener('click', event => {
    const target = event.target instanceof Element ? event.target : null;
    const link = target?.closest('a');
    if (link) {
      event.stopPropagation();
      if (window.AndroidInterface && window.AndroidInterface.openExternalUrl) {
        event.preventDefault();
        window.AndroidInterface.openExternalUrl(link.href);
      }
      return;
    }

    if (target?.closest('.mapboxgl-ctrl-attrib-button')) {
      updateAttributionToggle();
    }
  });
}

function initializeMapAttributionControl() {
  if (attributionControl) {
    attributionMutationObserver?.disconnect();
    map.removeControl(attributionControl);
  }
  attributionControl = new mapboxgl.AttributionControl({ compact: true });
  map.addControl(attributionControl, attributionControlPosition());
  bindMapAttributionControl();
}

function setMapAttributionConfiguration(position, rtl, termsLabel, improveLabel, showLabel, hideLabel) {
  const previousControlPosition = attributionControlPosition();
  mapAttributionPosition = position === 'top-start' ? 'top-start' : 'bottom-end';
  isRtl = rtl === true;
  mapAttributionLabels.terms = termsLabel;
  mapAttributionLabels.improve = improveLabel;
  mapAttributionLabels.show = showLabel;
  mapAttributionLabels.hide = hideLabel;
  document.documentElement.dir = isRtl ? 'rtl' : 'ltr';
  document.documentElement.dataset.mapAttributionPosition = mapAttributionPosition;

  if (previousControlPosition !== attributionControlPosition()) {
    initializeMapAttributionControl();
  } else {
    updateAttributionContent();
    updateAttributionToggle();
  }
}

initializeMapAttributionControl();

let userAvatarUrl = params.get('userAvatarUrl');
const defaultUserAccentColor = '#1A73E8';
const geolocationPermissionDeniedCode = 1;
const initialFastLocationOptions = {
  enableHighAccuracy: false,
  maximumAge: 30000,
  timeout: 2500,
};
const trackedLocationOptions = {
  enableHighAccuracy: true,
  maximumAge: 10000,
  timeout: 10000,
};
const locationSourcePriority = {
  fastInitial: 1,
  tracked: 2,
};
const staleLocationToleranceMs = 1500;
const minimumMeaningfulMoveMeters = 1.5;
const minimumMeaningfulHeadingDelta = 5;

function normalizeHexColor(color) {
  if (typeof color !== 'string') {
    return defaultUserAccentColor;
  }
  const trimmed = color.trim();
  const match = /^#?([0-9a-fA-F]{6})$/.exec(trimmed);
  if (!match) {
    return defaultUserAccentColor;
  }
  return `#${match[1].toUpperCase()}`;
}

function hexToRgba(color, alpha) {
  const hex = normalizeHexColor(color);
  const red = parseInt(hex.slice(1, 3), 16);
  const green = parseInt(hex.slice(3, 5), 16);
  const blue = parseInt(hex.slice(5, 7), 16);
  return `rgba(${red}, ${green}, ${blue}, ${alpha})`;
}

let userAccentColor = normalizeHexColor(params.get('userAccentColor'));

function applyAccentVariables(target, color) {
  target.style.setProperty('--user-accent-color', color);
  target.style.setProperty('--user-accent-shadow', hexToRgba(color, 0.24));
  target.style.setProperty('--user-accent-heading', hexToRgba(color, 0.65));
}

function setUserAccentColor(color) {
  userAccentColor = normalizeHexColor(color);
  applyAccentVariables(document.documentElement, userAccentColor);
  document.querySelectorAll('.user-location-overlay').forEach(element => {
    applyAccentVariables(element, userAccentColor);
  });
}

function normalizeUserAvatarUrl(url) {
  if (typeof url !== 'string') {
    return null;
  }
  const trimmed = url.trim();
  return trimmed ? trimmed : null;
}

setUserAccentColor(userAccentColor);
const markerHitPadding = 12;
const MapboxFeatureTag = 'Feature';
const MapboxFeatureCollectionTag = 'FeatureCollection';
const MapboxLineStringTag = 'LineString';
const MapboxGeoJsonSourceTag = 'geojson';
const MapboxLineLayerTag = 'line';
let currentMarkers = [];
let lastMoveWasUser = false;
let mapHasSettled = false;
let currentMapStyleUrl = initialMapStyleUrl;
let currentDarkTheme = initialDarkTheme;
const polylineSourceId = 'map-polylines';
let currentPolylines = [];
let polylineLayerCount = 0;
let isMapStyleReady = false;
let mapBoundsOptions = null;
// Recenter follows the live user location until another camera action or user gesture takes control.
let userLocationCenteringOptions = null;
let isCenteredOnUserLocation = false;
let lastUserLocationCameraUpdate = null;
let userLocationCenteringClearPending = false;

function setUserLocationCentered(isCentered) {
  const normalizedValue = Boolean(isCentered);
  if (normalizedValue === isCenteredOnUserLocation) {
    return;
  }
  isCenteredOnUserLocation = normalizedValue;
  if (window.AndroidInterface && window.AndroidInterface.notifyUserLocationCenteredChanged) {
    window.AndroidInterface.notifyUserLocationCenteredChanged(normalizedValue);
  }
}

function clearUserLocationCentering() {
  userLocationCenteringOptions = null;
  lastUserLocationCameraUpdate = null;
  userLocationCenteringClearPending = false;
  setUserLocationCentered(false);
}

function stopUserLocationCenteringForGesture() {
  userLocationCenteringOptions = null;
  lastUserLocationCameraUpdate = null;
  userLocationCenteringClearPending = true;
}

function setMapTheme(darkTheme, styleUrl) {
  applyDocumentTheme(darkTheme);
  currentDarkTheme = darkTheme;
  if (typeof styleUrl !== 'string' || !styleUrl || styleUrl === currentMapStyleUrl) {
    applyPolylines();
    return;
  }
  currentMapStyleUrl = styleUrl;
  isMapStyleReady = false;
  map.setStyle(styleUrl);
}

function updatePolylines(polylines) {
  currentPolylines = polylines;
  applyPolylines();
}

function applyPolylines() {
  if (!isMapStyleReady) {
    return;
  }

  updatePolylineSource();
  removeObsoletePolylineLayers();
  // Separate layers retain input order even when adjacent lines use different dash styles.
  currentPolylines.forEach((polyline, index) => updatePolylineLayer(polyline, index));
  polylineLayerCount = currentPolylines.length;
}

function updatePolylineSource() {
  const features = currentPolylines.map((polyline, index) => ({
    type: MapboxFeatureTag,
    properties: { index },
    geometry: { type: MapboxLineStringTag, coordinates: polyline.coordinates },
  }));
  const featureCollection = { type: MapboxFeatureCollectionTag, features };
  const source = map.getSource(polylineSourceId);
  if (source) {
    source.setData(featureCollection);
  } else if (features.length) {
    map.addSource(polylineSourceId, { type: MapboxGeoJsonSourceTag, data: featureCollection });
  }
}

function removeObsoletePolylineLayers() {
  for (let index = currentPolylines.length; index < polylineLayerCount; index++) {
    const layerId = `map-polyline-${index}`;
    if (map.getLayer(layerId)) {
      map.removeLayer(layerId);
    }
  }
}

function updatePolylineLayer(polyline, index) {
  const layerId = `map-polyline-${index}`;
  const paint = polylinePaint(polyline);
  if (map.getLayer(layerId)) {
    Object.entries(paint).forEach(([property, value]) => map.setPaintProperty(layerId, property, value));
    return;
  }
  map.addLayer({
    id: layerId,
    type: MapboxLineLayerTag,
    source: polylineSourceId,
    slot: 'top',
    filter: ['==', ['get', 'index'], index],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint,
  });
}

function polylinePaint(polyline) {
  return {
    'line-color': polyline.color ?? (currentDarkTheme ? '#ffffff' : '#111111'),
    'line-color-use-theme': polyline.color != null || currentDarkTheme ? 'none' : 'default',
    'line-width': polyline.width ?? 3,
    'line-opacity': polyline.opacity ?? (currentDarkTheme ? 1 : 0.9),
    'line-occlusion-opacity': 1,
    'line-emissive-strength': currentDarkTheme ? 1 : 0,
    'line-dasharray': polyline.dashed ? [0.1, 1.2] : [1, 0],
  };
}

map.on('style.load', () => {
  isMapStyleReady = true;
  applyPolylines();
});

function restorePolylinesAfterStyleDiff() {
  if (!isMapStyleReady && map.isStyleLoaded()) {
    isMapStyleReady = true;
    applyPolylines();
  }
}

// A successful style diff can omit style.load. Wait for any resources it still needs.
map.on('styledata', restorePolylinesAfterStyleDiff);
map.on('idle', restorePolylinesAfterStyleDiff);

function setMapLanguage(language) {
  document.documentElement.lang = language || 'en';
  map.setLanguage(language);
}

function notifyViewportChanged(isUserInitiated) {
  if (!window.AndroidInterface || !window.AndroidInterface.notifyViewportChanged) {
    return;
  }
  const bounds = map.getBounds();
  const center = map.getCenter();
  const northEast = bounds.getNorthEast();
  const southWest = bounds.getSouthWest();
  const payload = {
    latitude: center.lat,
    longitude: center.lng,
    latitudeSpan: Math.abs(northEast.lat - southWest.lat),
    longitudeSpan: Math.abs(northEast.lng - southWest.lng),
    isUserInitiated: Boolean(isUserInitiated),
  };
  window.AndroidInterface.notifyViewportChanged(JSON.stringify(payload));
}

const starIcon = `
  <svg class="star-icon" viewBox="0 0 24 24" aria-hidden="true">
    <path
      d="M10.258 1.555c.765-1.358 2.719-1.358 3.484 0l2.555 4.531 5.099 1.029c1.527.308 2.131 2.167 1.077 3.314l-3.521 3.83.597 5.168c.179 1.547-1.402 2.696-2.819 2.048L12 19.31l-4.73 2.165c-1.417.648-2.998-.501-2.819-2.048l.597-5.168-3.521-3.83C0.473 9.282 1.077 7.423 2.604 7.115l5.099-1.029z"
      fill="currentColor"
    />
  </svg>
`;

const parsePinLabel = (() => {
  // The model generates pin-label emoji. Follow web's lenient validation, including arbitrary
  // joins (😀‍🚗) and unsupported skin-tone combinations (😀🏽).
  // Same sequence pattern as https://github.com/slevithan/emoji-regex-xs/blob/v2.0.1/index.js
  const emojiComponent = String.raw`\p{Emoji}(?:\p{EMod}|[\u{E0020}-\u{E007E}]+\u{E007F}|\uFE0F?\u20E3?)`;
  const emojiPattern = new RegExp(
    String.raw`^(?:\p{RI}{2}|(?![#*\d](?!\uFE0F?\u20E3))${emojiComponent}(?:\u200D${emojiComponent})*)$`,
    'u',
  );

  return (value) => {
    const text = value?.trim();
    if (!text) {
      return null;
    }
    const isEmoji = emojiPattern.test(text);
    if (!isEmoji && (Array.from(text).length > 8 || /^\p{L}$/u.test(text))) {
      return null;
    }
    return { text, isEmoji };
  };
})();

function getPadding(bottomPaddingOverride) {
  const bottomPadding = typeof bottomPaddingOverride === 'number' ? bottomPaddingOverride : initialBottomPadding;
  return {
    top: defaultPadding,
    bottom: defaultPadding + bottomPadding,
    left: defaultPadding,
    right: defaultPadding,
  };
}

function normalizeFocusVerticalFraction(focusVerticalFraction) {
  if (!Number.isFinite(focusVerticalFraction)) {
    return 0.5;
  }
  return Math.min(1, Math.max(0, focusVerticalFraction));
}

function getEffectiveBottomPadding(bottomPaddingOverride) {
  const rawBottomPadding = typeof bottomPaddingOverride === 'number' ? bottomPaddingOverride : initialBottomPadding;
  const mapHeight = map.getContainer()?.clientHeight || window.innerHeight || 0;
  const maxBottomPadding = Math.max(0, mapHeight - (defaultPadding * 2));
  return Math.min(Math.max(0, rawBottomPadding), maxBottomPadding);
}

function getOffset(bottomPaddingOverride, focusVerticalFractionOverride) {
  const bottomPadding = getEffectiveBottomPadding(bottomPaddingOverride);
  const focusVerticalFraction = normalizeFocusVerticalFraction(
    typeof focusVerticalFractionOverride === 'number' ? focusVerticalFractionOverride : initialFocusVerticalFraction,
  );
  const mapHeight = map.getContainer()?.clientHeight || window.innerHeight || 0;
  const paddedTop = defaultPadding;
  const paddedBottom = defaultPadding + bottomPadding;
  const paddedHeight = Math.max(0, mapHeight - paddedTop - paddedBottom);
  const paddedCenterY = paddedTop + (paddedHeight / 2);
  const targetY = paddedTop + (paddedHeight * focusVerticalFraction);
  return [0, targetY - paddedCenterY];
}

function updateCamera(
  longitude,
  latitude,
  zoom,
  bottomPaddingOverride,
  focusVerticalFractionOverride,
  centersUserLocation = false,
) {
  mapBoundsOptions = null;
  if (!centersUserLocation) {
    userLocationCenteringOptions = null;
  }
  setUserLocationCentered(centersUserLocation);
  const bottomPadding = getEffectiveBottomPadding(bottomPaddingOverride);
  const cameraOptions = {
    center: [longitude, latitude],
    offset: getOffset(bottomPadding, focusVerticalFractionOverride),
    padding: getPadding(bottomPadding),
  };
  if (typeof zoom === 'number') {
    cameraOptions.zoom = zoom;
  }
  map.flyTo(cameraOptions);
}

function fitMapContentToBounds(
  markers,
  animate,
  bottomPaddingOverride,
  focusVerticalFractionOverride,
) {
  const coordinates = markers.map(marker => [marker.longitude, marker.latitude])
    .concat(currentPolylines.flatMap(polyline => polyline.coordinates));
  if (!coordinates.length) {
    mapBoundsOptions = null;
    return;
  }
  mapBoundsOptions = {
    coordinates,
    bottomPaddingOverride,
    focusVerticalFractionOverride,
  };
  fitCoordinatesToBounds(mapBoundsOptions, animate);
}

function fitCoordinatesToBounds({ coordinates, bottomPaddingOverride, focusVerticalFractionOverride }, animate) {
  clearUserLocationCentering();
  const bounds = new mapboxgl.LngLatBounds();
  coordinates.forEach(coordinate => bounds.extend(coordinate));
  map.fitBounds(bounds, {
    animate: Boolean(animate),
    padding: getPadding(getEffectiveBottomPadding(bottomPaddingOverride)),
    offset: getOffset(bottomPaddingOverride, focusVerticalFractionOverride),
  });
}

let businessMarkers = [];
let geolocateControl = null;
let geolocateLoadHandler = null;
let geolocateEventHandler = null;
let geolocateErrorHandler = null;
let geolocateTrackingStartHandler = null;
let geolocateTrackingEndHandler = null;
let isGeolocateTrackingActive = false;
let userOverlayMarker = null;
let lastMarkerIds = new Set();
const markerSelectionTransitionDurationMs = Number.parseFloat(
  getComputedStyle(document.documentElement).getPropertyValue('--marker-selection-transition-duration'),
);
// Markers are recreated on every update, so retain their prior state and in-flight transition timing.
let lastMarkerStates = new Map();
let markerSelectionTransitionStartTimes = new Map();
let initialFastLocationRequestInFlight = false;
let lastAppliedLocationUpdate = null;
let hasReceivedTrackedLocation = false;

function setLastAppliedLocationUpdate(locationUpdate) {
  const wasAvailable = lastAppliedLocationUpdate !== null;
  lastAppliedLocationUpdate = locationUpdate;
  const isAvailable = locationUpdate !== null;
  if (
    wasAvailable !== isAvailable &&
    window.AndroidInterface &&
    window.AndroidInterface.notifyUserLocationAvailabilityChanged
  ) {
    window.AndroidInterface.notifyUserLocationAvailabilityChanged(isAvailable);
  }
}

function updateMarkers(markers) {
  if (markers.length === 0 && currentPolylines.length === 0) {
    mapBoundsOptions = null;
  }
  businessMarkers.forEach(marker => marker.remove());
  businessMarkers = [];

  currentMarkers = [];
  const nextMarkerIds = new Set(markers.map(marker => marker.id));
  const updateTimestamp = performance.now();
  markers.forEach(marker => {
    const lastMarkerState = lastMarkerStates.get(marker.id);
    const markerElement = document.createElement('div');
    markerElement.className = 'custom-marker';
    markerElement.classList.toggle('selected', marker.selected);
    markerElement.classList.toggle('unselected', !marker.selected);
    markerElement.classList.toggle('minimized', marker.minimized);
    if (lastMarkerState?.minimized && !lastMarkerState.selected && marker.minimized && marker.selected) {
      markerSelectionTransitionStartTimes.set(marker.id, updateTimestamp);
    }
    const transitionStartTime = markerSelectionTransitionStartTimes.get(marker.id);
    const transitionElapsedMs = transitionStartTime === undefined ? null : updateTimestamp - transitionStartTime;
    if (
      marker.minimized &&
      marker.selected &&
      transitionElapsedMs !== null &&
      transitionElapsedMs < markerSelectionTransitionDurationMs
    ) {
      markerElement.classList.add('marker-expand-from-minimized');
      markerElement.style.setProperty('--marker-selection-transition-delay', `${-transitionElapsedMs}ms`);
    } else {
      markerSelectionTransitionStartTimes.delete(marker.id);
    }
    if (!lastMarkerIds.has(marker.id)) {
      markerElement.classList.add('marker-fade-in');
    }
    markerElement.dataset.id = marker.id;
    const showMarkerDetails = !marker.minimized || marker.selected;
    const pillElement = document.createElement('div');
    pillElement.className = 'marker-pill';
    if (showMarkerDetails) {
      const detailsElement = document.createElement('span');
      detailsElement.className = 'marker-details';
      const pinLabel = parsePinLabel(marker.pin_label);
      if (pinLabel) {
        detailsElement.textContent = pinLabel.text;
        detailsElement.classList.add('marker-pin-label');
        detailsElement.classList.toggle('marker-pin-emoji', pinLabel.isEmoji);
      } else {
        detailsElement.innerHTML = `${starIcon} ${marker.rating !== null && marker.rating !== undefined ? marker.rating.toFixed(1) : '---'}`;
      }
      pillElement.appendChild(detailsElement);
    }
    markerElement.appendChild(pillElement);

    if (marker.label && showMarkerDetails) {
      const labelElement = document.createElement('div');
      labelElement.className = 'marker-label';
      labelElement.textContent = marker.label;
      markerElement.appendChild(labelElement);
    }

    const mapMarker = new mapboxgl.Marker(markerElement)
      .setLngLat([marker.longitude, marker.latitude])
      .addTo(map);

    businessMarkers.push(mapMarker);
    currentMarkers.push({
      id: marker.id,
      selected: marker.selected,
      element: markerElement,
    });
  });

  lastMarkerIds = nextMarkerIds;
  for (const markerId of markerSelectionTransitionStartTimes.keys()) {
    if (!nextMarkerIds.has(markerId)) {
      markerSelectionTransitionStartTimes.delete(markerId);
    }
  }
  lastMarkerStates = new Map(markers.map(marker => [marker.id, {
    minimized: marker.minimized,
    selected: marker.selected,
  }]));
}

function createUserOverlayElement() {
  const wrapper = document.createElement('div');
  wrapper.className = 'user-location-overlay';
  wrapper.style.setProperty('--user-accent-color', userAccentColor);
  wrapper.style.setProperty('--user-accent-shadow', hexToRgba(userAccentColor, 0.24));
  wrapper.style.setProperty('--user-accent-heading', hexToRgba(userAccentColor, 0.65));

  const headingElement = document.createElement('div');
  headingElement.className = 'user-location-heading';
  wrapper.appendChild(headingElement);

  if (userAvatarUrl) {
    const avatarElement = document.createElement('img');
    avatarElement.className = 'user-location-avatar';
    avatarElement.src = userAvatarUrl;
    avatarElement.alt = '';
    wrapper.appendChild(avatarElement);
    return wrapper;
  }

  const dotElement = document.createElement('div');
  dotElement.className = 'user-location-accent-dot';
  wrapper.appendChild(dotElement);
  return wrapper;
}

function removeUserOverlayMarker(preserveLocation = false) {
  if (userOverlayMarker) {
    userOverlayMarker.remove();
    userOverlayMarker = null;
  }
  if (!preserveLocation) {
    setLastAppliedLocationUpdate(null);
  }
}

function refreshUserOverlayMarker() {
  if (!lastAppliedLocationUpdate) {
    return;
  }
  const locationUpdate = lastAppliedLocationUpdate;
  const { longitude, latitude, heading } = locationUpdate;
  removeUserOverlayMarker(true);
  updateUserOverlay(longitude, latitude, heading);
}

function setUserAvatarUrl(url) {
  const normalizedAvatarUrl = normalizeUserAvatarUrl(url);
  if (userAvatarUrl === normalizedAvatarUrl) {
    return;
  }
  userAvatarUrl = normalizedAvatarUrl;
  refreshUserOverlayMarker();
}

function updateUserOverlayHeading(heading) {
  if (!userOverlayMarker) {
    return;
  }
  const element = userOverlayMarker.getElement();
  if (Number.isFinite(heading)) {
    element.style.setProperty('--user-heading', `${heading}deg`);
    element.classList.add('has-heading');
    return;
  }
  element.style.removeProperty('--user-heading');
  element.classList.remove('has-heading');
}

function updateUserOverlay(longitude, latitude, heading) {
  const hasLocation = Number.isFinite(longitude) && Number.isFinite(latitude);
  if (!hasLocation) {
    removeUserOverlayMarker();
    return;
  }

  const lngLat = [longitude, latitude];
  if (!userOverlayMarker) {
    const element = createUserOverlayElement();
    userOverlayMarker = new mapboxgl.Marker({
      element,
      anchor: 'center',
    })
      .setLngLat(lngLat)
      .addTo(map);
  } else {
    userOverlayMarker.setLngLat(lngLat);
  }
  updateUserOverlayHeading(heading);
}

function normalizePositionTimestampMs(positionTimestampMs) {
  if (Number.isFinite(positionTimestampMs)) {
    return positionTimestampMs;
  }
  return Date.now();
}

function normalizeHeading(heading) {
  if (!Number.isFinite(heading)) {
    return null;
  }
  const normalized = heading % 360;
  return normalized < 0 ? normalized + 360 : normalized;
}

function estimateDistanceMeters(first, second) {
  const toRadians = value => (value * Math.PI) / 180;
  const latitudeDeltaRadians = toRadians(first.latitude - second.latitude);
  const longitudeDeltaRadians = toRadians(first.longitude - second.longitude);
  const averageLatitudeRadians = toRadians((first.latitude + second.latitude) / 2);
  const earthRadiusMeters = 6371000;
  const x = longitudeDeltaRadians * Math.cos(averageLatitudeRadians);
  const y = latitudeDeltaRadians;
  return Math.hypot(x, y) * earthRadiusMeters;
}

function hasMeaningfulLocationDelta(next, previous) {
  const distanceMeters = estimateDistanceMeters(next, previous);
  if (distanceMeters >= minimumMeaningfulMoveMeters) {
    return true;
  }
  const previousHeading = previous.heading;
  const nextHeading = next.heading;
  if (!Number.isFinite(previousHeading) || !Number.isFinite(nextHeading)) {
    return false;
  }
  const headingDelta = Math.abs(nextHeading - previousHeading);
  const wrappedHeadingDelta = Math.min(headingDelta, 360 - headingDelta);
  return wrappedHeadingDelta >= minimumMeaningfulHeadingDelta;
}

function shouldApplyLocationUpdate(nextUpdate) {
  if (!lastAppliedLocationUpdate) {
    return true;
  }
  const latestUpdate = lastAppliedLocationUpdate;
  const updateIsStaleByTimestamp =
    nextUpdate.positionTimestampMs + staleLocationToleranceMs < latestUpdate.positionTimestampMs;
  if (updateIsStaleByTimestamp) {
    return false;
  }
  const updateHasLowerPriorityWithoutNewerTimestamp =
    nextUpdate.sourcePriority < latestUpdate.sourcePriority &&
    nextUpdate.positionTimestampMs <= latestUpdate.positionTimestampMs + staleLocationToleranceMs;
  if (updateHasLowerPriorityWithoutNewerTimestamp) {
    return false;
  }
  const hasMeaningfulDelta = hasMeaningfulLocationDelta(nextUpdate, latestUpdate);
  if (!hasMeaningfulDelta && nextUpdate.sourcePriority <= latestUpdate.sourcePriority) {
    return false;
  }
  return true;
}

function applyLocationUpdate({
  longitude,
  latitude,
  heading,
  source,
  sourcePriority,
  positionTimestampMs,
}) {
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) {
    return;
  }
  const candidate = {
    longitude,
    latitude,
    heading: normalizeHeading(heading),
    source,
    sourcePriority,
    positionTimestampMs: normalizePositionTimestampMs(positionTimestampMs),
  };
  if (!shouldApplyLocationUpdate(candidate)) {
    return;
  }
  updateUserOverlay(candidate.longitude, candidate.latitude, candidate.heading);
  setLastAppliedLocationUpdate(candidate);
  const shouldUpdateCenteredCamera =
    userLocationCenteringOptions &&
    (!isCenteredOnUserLocation ||
      !lastUserLocationCameraUpdate ||
      estimateDistanceMeters(candidate, lastUserLocationCameraUpdate) >= minimumMeaningfulMoveMeters);
  if (shouldUpdateCenteredCamera) {
    updateCamera(
      candidate.longitude,
      candidate.latitude,
      null,
      userLocationCenteringOptions.bottomPadding,
      userLocationCenteringOptions.focusVerticalFraction,
      true,
    );
    lastUserLocationCameraUpdate = candidate;
  }
}

function recenterOnUserLocation(bottomPadding, focusVerticalFraction) {
  userLocationCenteringOptions = {
    bottomPadding,
    focusVerticalFraction,
  };
  lastUserLocationCameraUpdate = null;
  userLocationCenteringClearPending = false;
  if (lastAppliedLocationUpdate) {
    const locationUpdate = lastAppliedLocationUpdate;
    updateCamera(
      locationUpdate.longitude,
      locationUpdate.latitude,
      null,
      bottomPadding,
      focusVerticalFraction,
      true,
    );
    lastUserLocationCameraUpdate = locationUpdate;
    return;
  }
  ensureGeolocateTracking();
}

function ensureGeolocateTracking() {
  if (!geolocateControl || isGeolocateTrackingActive) {
    return;
  }
  geolocateControl.trigger();
}

function clearGeolocateControl() {
  clearUserLocationCentering();
  if (geolocateLoadHandler) {
    map.off('load', geolocateLoadHandler);
    geolocateLoadHandler = null;
  }
  if (geolocateControl && geolocateEventHandler) {
    geolocateControl.off('geolocate', geolocateEventHandler);
    geolocateEventHandler = null;
  }
  if (geolocateControl && geolocateErrorHandler) {
    geolocateControl.off('error', geolocateErrorHandler);
    geolocateErrorHandler = null;
  }
  if (geolocateControl && geolocateTrackingStartHandler) {
    geolocateControl.off('trackuserlocationstart', geolocateTrackingStartHandler);
    geolocateTrackingStartHandler = null;
  }
  if (geolocateControl && geolocateTrackingEndHandler) {
    geolocateControl.off('trackuserlocationend', geolocateTrackingEndHandler);
    geolocateTrackingEndHandler = null;
  }
  isGeolocateTrackingActive = false;
  if (!geolocateControl) {
    removeUserOverlayMarker();
    hasReceivedTrackedLocation = false;
    return;
  }
  map.removeControl(geolocateControl);
  geolocateControl = null;
  hasReceivedTrackedLocation = false;
  removeUserOverlayMarker();
}

function setUserLocationEnabled(enabled) {
  if (!enabled) {
    clearGeolocateControl();
    return;
  }

  if (geolocateControl) {
    return;
  }

  if (navigator.geolocation && !initialFastLocationRequestInFlight) {
    initialFastLocationRequestInFlight = true;
    navigator.geolocation.getCurrentPosition(
      position => {
        initialFastLocationRequestInFlight = false;
        const coords = position && position.coords;
        if (!coords) {
          return;
        }
        applyLocationUpdate({
          longitude: coords.longitude,
          latitude: coords.latitude,
          heading: coords.heading,
          source: 'fast_initial',
          sourcePriority: locationSourcePriority.fastInitial,
          positionTimestampMs: position?.timestamp,
        });
      },
      error => {
        initialFastLocationRequestInFlight = false;
      },
      initialFastLocationOptions,
    );
  }

  geolocateControl = new mapboxgl.GeolocateControl({
    trackUserLocation: true,
    // We render our own user-location marker/heading overlay, so keep Mapbox's
    // tracking behavior but disable its built-in puck UI to avoid duplicates.
    showUserLocation: false,
    showUserHeading: false,
    showButton: false,
    followUserLocation: false,
    positionOptions: trackedLocationOptions,
  });
  map.addControl(geolocateControl);

  geolocateEventHandler = event => {
    const coords = event && event.coords;
    if (!coords) {
      return;
    }
    hasReceivedTrackedLocation = true;
    applyLocationUpdate({
      longitude: coords.longitude,
      latitude: coords.latitude,
      heading: coords.heading,
      source: 'tracked',
      sourcePriority: locationSourcePriority.tracked,
      positionTimestampMs: event?.timestamp,
    });
  };
  geolocateErrorHandler = error => {
    if (error?.code === geolocationPermissionDeniedCode) {
      isGeolocateTrackingActive = false;
      clearUserLocationCentering();
      removeUserOverlayMarker();
    }
  };
  geolocateTrackingStartHandler = () => {
    isGeolocateTrackingActive = true;
  };
  geolocateTrackingEndHandler = () => {
    isGeolocateTrackingActive = false;
  };
  geolocateControl.on('geolocate', geolocateEventHandler);
  geolocateControl.on('error', geolocateErrorHandler);
  geolocateControl.on('trackuserlocationstart', geolocateTrackingStartHandler);
  geolocateControl.on('trackuserlocationend', geolocateTrackingEndHandler);

  // Trigger immediately to avoid waiting for map style load before first location fix.
  ensureGeolocateTracking();

  if (map.isStyleLoaded && map.isStyleLoaded()) {
    return;
  }

  geolocateLoadHandler = () => {
    if (hasReceivedTrackedLocation) {
      if (geolocateLoadHandler) {
        map.off('load', geolocateLoadHandler);
        geolocateLoadHandler = null;
      }
      return;
    }
    ensureGeolocateTracking();
    if (geolocateLoadHandler) {
      map.off('load', geolocateLoadHandler);
      geolocateLoadHandler = null;
    }
  };
  map.on('load', geolocateLoadHandler);
}

function findMarkerHit(point) {
  if (!currentMarkers.length) {
    return null;
  }
  const canvasRect = map.getCanvas().getBoundingClientRect();
  const clickX = canvasRect.left + point.x;
  const clickY = canvasRect.top + point.y;
  const candidates = currentMarkers
    .map((marker, index) => {
      const markerRect = marker.element.getBoundingClientRect();
      const pill = marker.element.querySelector('.marker-pill');
      const pillRect = pill.getBoundingClientRect();
      let pointerHit = false;
      if (marker.selected) {
        // Hit-test the selected pointer's rounded square before its CSS 45-degree rotation.
        const pointer = getComputedStyle(pill, '::after');
        const halfSize = Number.parseFloat(pointer.width) / 2;
        const radius = Number.parseFloat(pointer.borderRadius);
        const pointerX = pillRect.left + pillRect.width / 2;
        const pointerY = pillRect.bottom - Number.parseFloat(getComputedStyle(pill).borderBottomWidth) -
          Number.parseFloat(pointer.bottom) - halfSize;
        const pointerDx = clickX - pointerX;
        const pointerDy = clickY - pointerY;
        const cornerX = Math.max(Math.abs((pointerDx + pointerDy) / Math.SQRT2) - halfSize + radius, 0);
        const cornerY = Math.max(Math.abs((pointerDy - pointerDx) / Math.SQRT2) - halfSize + radius, 0);
        pointerHit = Math.hypot(cornerX, cornerY) <= radius;
      }
      const labelRect = marker.element.querySelector('.marker-label')?.getBoundingClientRect();
      const left = Math.min(markerRect.left, labelRect?.left ?? markerRect.left) - markerHitPadding;
      const right = Math.max(markerRect.right, labelRect?.right ?? markerRect.right) + markerHitPadding;
      const top = Math.min(markerRect.top, labelRect?.top ?? markerRect.top) - markerHitPadding;
      const bottom = Math.max(markerRect.bottom, labelRect?.bottom ?? markerRect.bottom) + markerHitPadding;
      if (clickX < left || clickX > right || clickY < top || clickY > bottom) {
        return null;
      }
      const centerX = markerRect.left + markerRect.width / 2;
      const centerY = markerRect.top + markerRect.height / 2;
      const dx = clickX - centerX;
      const dy = clickY - centerY;
      return {
        marker,
        distance: Math.hypot(dx, dy),
        directHit: pointerHit || [pillRect, labelRect].some(rect => rect &&
          clickX >= rect.left && clickX <= rect.right &&
          clickY >= rect.top && clickY <= rect.bottom),
        zIndex: Number.parseInt(getComputedStyle(marker.element).zIndex, 10) || 0,
        paintOrder: index,
      };
    })
    .filter(Boolean);

  if (!candidates.length) {
    return null;
  }

  candidates.sort((a, b) => {
    // Padded targets can overlap another marker's visible pill or label.
    // Prefer the directly touched marker in the order it is painted.
    if (a.directHit !== b.directHit) {
      return a.directHit ? -1 : 1;
    }
    if (a.directHit) {
      return b.zIndex - a.zIndex || b.paintOrder - a.paintOrder;
    }
    if (a.distance !== b.distance) {
      return a.distance - b.distance;
    }
    if (a.marker.selected === b.marker.selected) {
      return 0;
    }
    return a.marker.selected ? 1 : -1;
  });

  return candidates[0].marker;
}

map.on('dragstart', event => {
  if (!event || !event.originalEvent) {
    return;
  }
  stopUserLocationCenteringForGesture();
  lastMoveWasUser = true;
});

map.on('zoomstart', event => {
  if (!event || !event.originalEvent) {
    return;
  }
  stopUserLocationCenteringForGesture();
  lastMoveWasUser = true;
});

map.on('rotatestart', event => {
  if (!event || !event.originalEvent) {
    return;
  }
  stopUserLocationCenteringForGesture();
  lastMoveWasUser = true;
});

map.on('pitchstart', event => {
  if (!event || !event.originalEvent) {
    return;
  }
  stopUserLocationCenteringForGesture();
  lastMoveWasUser = true;
});

map.on('movestart', event => {
  // `originalEvent` can be missing in WebView contexts; preserve any prior user signal.
  const originalEvent = event.originalEvent;
  const isViewportResize = ['resize', 'orientationchange', 'fullscreenchange', 'webkitfullscreenchange']
    .includes(originalEvent?.type);
  const shouldSetUserMove = Boolean(originalEvent) && !isViewportResize;
  lastMoveWasUser = lastMoveWasUser || shouldSetUserMove;
});

map.on('resize', () => {
  if (!refitBoundsOnResize || !mapBoundsOptions) {
    return;
  }
  // Mapbox has updated its viewport before emitting resize, so fit against the new dimensions.
  fitCoordinatesToBounds(mapBoundsOptions, false);
});

map.on('moveend', () => {
  if (!mapHasSettled) {
    lastMoveWasUser = false;
    return;
  }
  notifyViewportChanged(lastMoveWasUser);
  if (userLocationCenteringClearPending) {
    userLocationCenteringClearPending = false;
    setUserLocationCentered(false);
  }
  lastMoveWasUser = false;
});

map.on('click', event => {
  const hitMarker = findMarkerHit(event.point);
  if (!hitMarker) {
    if (window.AndroidInterface && window.AndroidInterface.notifyMapClick) {
      window.AndroidInterface.notifyMapClick();
    }
    return;
  }
  if (window.AndroidInterface && window.AndroidInterface.notifyMarkerSelect) {
    window.AndroidInterface.notifyMarkerSelect(hitMarker.id);
  }
});

const markers = JSON.parse(decodeURIComponent(params.get('markers')));
updateMarkers(markers);
const polylines = JSON.parse(decodeURIComponent(params.get('polylines') || '[]'));
updatePolylines(polylines);

const showUserLocation = params.get('showUserLocation') === 'true';
setUserLocationEnabled(showUserLocation);

// If no location provided, frame every marker and rendered line.
if (!latitude || !longitude) {
  fitMapContentToBounds(markers, false);
}

map.once('idle', () => {
  // Mapbox can emit move events during initial layout/camera adjustments. Wait until
  // the first idle to consider the map settled, then seed one viewport update.
  mapHasSettled = true;
  lastMoveWasUser = false;
  notifyViewportChanged(userLocationCenteringClearPending);
  if (userLocationCenteringClearPending) {
    userLocationCenteringClearPending = false;
    setUserLocationCentered(false);
  }
  if (window.AndroidInterface && window.AndroidInterface.notifyInitialContentsLoaded) {
    window.AndroidInterface.notifyInitialContentsLoaded();
  }
});
