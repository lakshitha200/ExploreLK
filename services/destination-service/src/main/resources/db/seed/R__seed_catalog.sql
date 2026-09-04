-- ════════════════════════════════════════════════════════════════════════════
-- Development seed — real Sri Lankan catalog content.
--
-- NOT a versioned migration. This file lives in classpath:db/seed, a location
-- that only application-dev.yml adds to spring.flyway.locations, so production
-- never sees it. Versioned migrations are the schema; this is content.
--
-- Repeatable (R__): Flyway re-runs it whenever its checksum changes, after every
-- versioned migration. That means it MUST be idempotent, which is why every
-- statement is an upsert against a fixed UUID rather than a plain INSERT.
--
-- Fixed UUIDs also make the data stable across rebuilds: an id you used in a
-- Postman request last week still resolves after a `docker compose down -v`.
--
--   d0000000-…  destinations
--   a0000000-…  attractions
--
-- Coordinates are real. Fees are indicative LKR and go stale — they are seed
-- data for building against, not a price list.
-- ════════════════════════════════════════════════════════════════════════════

-- ── Destinations ────────────────────────────────────────────────────────────
INSERT INTO destinations
    (id, slug, name, district, province, summary, description,
     latitude, longitude, recommended_days, popularity_score, status)
VALUES
    ('d0000000-0000-4000-8000-000000000001', 'colombo', 'Colombo', 'Colombo', 'Western',
     'The commercial capital — colonial streets, street food and the Indian Ocean at sunset.',
     'Most journeys start or end here. Colombo mixes Dutch and British colonial architecture with modern high-rises, temples, markets and a long seafront promenade. Two days is enough before heading inland.',
     6.927100, 79.861200, 2, 85, 'PUBLISHED'),

    ('d0000000-0000-4000-8000-000000000002', 'kandy', 'Kandy', 'Kandy', 'Central',
     'The last royal capital, built around a lake and the country''s most sacred temple.',
     'Kandy sits in a bowl of forested hills at 500 m. It holds the Temple of the Sacred Tooth Relic, the Royal Botanical Gardens at Peradeniya, and the nightly Kandyan dance performances. The hill-country train south starts here.',
     7.290600, 80.633700, 2, 95, 'PUBLISHED'),

    ('d0000000-0000-4000-8000-000000000003', 'nuwara-eliya', 'Nuwara Eliya', 'Nuwara Eliya', 'Central',
     'Tea country at 1,900 m — cool air, estate bungalows and the road to Horton Plains.',
     'Known as Little England for its cottages and hedgerows. The surrounding hills are covered in tea estates that can be toured and tasted, and Horton Plains National Park with World''s End is a short drive away. Bring warm clothes; nights drop below 10 °C.',
     6.949700, 80.789100, 2, 80, 'PUBLISHED'),

    ('d0000000-0000-4000-8000-000000000004', 'ella', 'Ella', 'Badulla', 'Uva',
     'Misty hill town of tea estates, short hikes and the most photographed bridge in the country.',
     'Ella is small, walkable and surrounded by viewpoints reachable in under two hours on foot. The train ride in from Nuwara Eliya or Kandy is widely considered the most scenic in Asia. Base yourself here for Nine Arches Bridge, Little Adam''s Peak and Ravana Falls.',
     6.866700, 81.046600, 2, 92, 'PUBLISHED'),

    ('d0000000-0000-4000-8000-000000000005', 'sigiriya', 'Sigiriya', 'Matale', 'Central',
     'A fifth-century palace on top of a 200 m rock, with frescoes halfway up.',
     'Sigiriya Rock is the country''s most famous archaeological site — water gardens at the base, mirror wall and frescoes on the way, and the ruins of King Kashyapa''s palace on the summit. Climb at dawn: it is 1,200 steps and the heat arrives early.',
     7.957000, 80.760300, 1, 90, 'PUBLISHED'),

    ('d0000000-0000-4000-8000-000000000006', 'anuradhapura', 'Anuradhapura', 'Anuradhapura', 'North Central',
     'A sacred city of stupas and monasteries, capital for over a thousand years.',
     'The first ancient capital, and still an active pilgrimage site. The ruins are spread over a wide area — hire a bicycle or a tuk-tuk. Sri Maha Bodhi is grown from a cutting of the tree the Buddha sat under, making it among the oldest documented trees on earth.',
     8.311400, 80.403700, 1, 70, 'PUBLISHED'),

    ('d0000000-0000-4000-8000-000000000007', 'yala', 'Yala', 'Hambantota', 'Southern',
     'The best-known national park — leopards, elephants, sloth bears and lagoons.',
     'Yala has one of the highest leopard densities in the world, concentrated in Block 1. Safaris run at dawn and mid-afternoon and are booked through the park entrance or a lodge. The park closes annually around September for the dry season.',
     6.372800, 81.501600, 2, 88, 'PUBLISHED'),

    ('d0000000-0000-4000-8000-000000000008', 'mirissa', 'Mirissa', 'Matara', 'Southern',
     'A crescent of sand on the south coast, and the launching point for whale watching.',
     'Mirissa is a small fishing town that became the country''s whale-watching capital — blue whales and sperm whales pass offshore between November and April. Outside the season it is a quiet surf and swimming beach.',
     5.948300, 80.458900, 2, 86, 'PUBLISHED'),

    ('d0000000-0000-4000-8000-000000000009', 'galle', 'Galle', 'Galle', 'Southern',
     'A walled Dutch fort on a headland, still lived in, still working.',
     'Galle Fort is a UNESCO World Heritage Site and the best-preserved colonial fortification in South Asia. Inside the walls are cafés, boutiques, museums and a lighthouse; the ramparts are the evening walk. Easy to combine with the southern beaches.',
     6.032900, 80.216800, 2, 89, 'PUBLISHED'),

    ('d0000000-0000-4000-8000-00000000000a', 'arugam-bay', 'Arugam Bay', 'Ampara', 'Eastern',
     'The east coast surf town — a long right-hand point break and very little else.',
     'Arugam Bay runs on a single road. The surf season is May to September, the opposite of the south coast, which makes it the right call for a mid-year trip. Elephants are regularly seen on the lagoon road at dusk.',
     6.840000, 81.836700, 3, 74, 'PUBLISHED')

ON CONFLICT (id) DO UPDATE SET
    slug             = EXCLUDED.slug,
    name             = EXCLUDED.name,
    district         = EXCLUDED.district,
    province         = EXCLUDED.province,
    summary          = EXCLUDED.summary,
    description      = EXCLUDED.description,
    latitude         = EXCLUDED.latitude,
    longitude        = EXCLUDED.longitude,
    recommended_days = EXCLUDED.recommended_days,
    popularity_score = EXCLUDED.popularity_score,
    status           = EXCLUDED.status,
    updated_at       = now();


-- ── Destination categories ──────────────────────────────────────────────────
-- Delete-then-insert rather than ON CONFLICT DO NOTHING: if a tag is removed
-- from this file, a re-run should drop it, not leave it behind. Scoped to the
-- seeded ids so anything an admin created by hand is untouched.
DELETE FROM destination_categories
 WHERE destination_id IN (SELECT id FROM destinations WHERE id::text LIKE 'd0000000-%');

INSERT INTO destination_categories (destination_id, category_code) VALUES
    ('d0000000-0000-4000-8000-000000000001', 'CULTURE'),
    ('d0000000-0000-4000-8000-000000000001', 'HISTORY'),

    ('d0000000-0000-4000-8000-000000000002', 'CULTURE'),
    ('d0000000-0000-4000-8000-000000000002', 'HISTORY'),
    ('d0000000-0000-4000-8000-000000000002', 'NATURE'),

    ('d0000000-0000-4000-8000-000000000003', 'NATURE'),
    ('d0000000-0000-4000-8000-000000000003', 'HIKING'),

    ('d0000000-0000-4000-8000-000000000004', 'NATURE'),
    ('d0000000-0000-4000-8000-000000000004', 'HIKING'),
    ('d0000000-0000-4000-8000-000000000004', 'ADVENTURE'),

    ('d0000000-0000-4000-8000-000000000005', 'HISTORY'),
    ('d0000000-0000-4000-8000-000000000005', 'CULTURE'),
    ('d0000000-0000-4000-8000-000000000005', 'HIKING'),

    ('d0000000-0000-4000-8000-000000000006', 'HISTORY'),
    ('d0000000-0000-4000-8000-000000000006', 'CULTURE'),

    ('d0000000-0000-4000-8000-000000000007', 'WILDLIFE'),
    ('d0000000-0000-4000-8000-000000000007', 'NATURE'),

    ('d0000000-0000-4000-8000-000000000008', 'BEACH'),
    ('d0000000-0000-4000-8000-000000000008', 'WILDLIFE'),

    ('d0000000-0000-4000-8000-000000000009', 'HISTORY'),
    ('d0000000-0000-4000-8000-000000000009', 'CULTURE'),
    ('d0000000-0000-4000-8000-000000000009', 'BEACH'),

    ('d0000000-0000-4000-8000-00000000000a', 'BEACH'),
    ('d0000000-0000-4000-8000-00000000000a', 'ADVENTURE');


-- ── Attractions ─────────────────────────────────────────────────────────────
-- visit_duration_minutes is the field the Itinerary Service will plan around, so
-- every seeded attraction has a realistic one.
INSERT INTO attractions
    (id, destination_id, slug, name, summary,
     latitude, longitude, visit_duration_minutes,
     is_free, entrance_fee, always_open, popularity_score, status)
VALUES
    -- Colombo
    ('a0000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-000000000001',
     'galle-face-green', 'Galle Face Green',
     'Half a kilometre of seafront lawn — kite sellers, isso wade stalls and the sunset crowd.',
     6.927100, 79.842500, 60, true, NULL, true, 70, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-000000000002', 'd0000000-0000-4000-8000-000000000001',
     'gangaramaya-temple', 'Gangaramaya Temple',
     'A working Buddhist temple and a museum of gifts, from ivory to vintage cars.',
     6.916600, 79.856300, 45, false, 400.00, false, 65, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-000000000003', 'd0000000-0000-4000-8000-000000000001',
     'national-museum', 'Colombo National Museum',
     'The country''s largest museum, in a colonial building — regalia, bronzes and masks.',
     6.910600, 79.861300, 90, false, 1000.00, false, 55, 'PUBLISHED'),

    -- Kandy
    ('a0000000-0000-4000-8000-000000000004', 'd0000000-0000-4000-8000-000000000002',
     'temple-of-the-tooth', 'Temple of the Sacred Tooth Relic',
     'The country''s holiest Buddhist site. Time your visit for a puja ceremony.',
     7.293600, 80.641300, 90, false, 2000.00, false, 98, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-000000000005', 'd0000000-0000-4000-8000-000000000002',
     'kandy-lake', 'Kandy Lake',
     'A man-made lake in the middle of town, ringed by a walkable path.',
     7.291700, 80.640300, 45, true, NULL, true, 60, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-000000000006', 'd0000000-0000-4000-8000-000000000002',
     'peradeniya-gardens', 'Royal Botanical Gardens, Peradeniya',
     '60 hectares of palms, orchids and a giant Javan fig, on a bend of the Mahaweli river.',
     7.268600, 80.596300, 120, false, 3000.00, false, 72, 'PUBLISHED'),

    -- Nuwara Eliya
    ('a0000000-0000-4000-8000-000000000007', 'd0000000-0000-4000-8000-000000000003',
     'gregory-lake', 'Gregory Lake',
     'Colonial-era reservoir with boat rides, horse carts and a lakeside walk.',
     6.957600, 80.771700, 90, false, 300.00, false, 58, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-000000000008', 'd0000000-0000-4000-8000-000000000003',
     'pedro-tea-estate', 'Pedro Tea Estate',
     'A working estate and factory tour, ending in a tasting above the plantation.',
     6.960000, 80.800000, 90, false, 1000.00, false, 64, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-000000000009', 'd0000000-0000-4000-8000-000000000003',
     'horton-plains-worlds-end', 'Horton Plains and World''s End',
     'A 9 km loop across highland plateau to a 870 m cliff edge. Start before 07:00 or it clouds over.',
     6.809100, 80.798900, 240, false, 4000.00, false, 82, 'PUBLISHED'),

    -- Ella
    ('a0000000-0000-4000-8000-00000000000a', 'd0000000-0000-4000-8000-000000000004',
     'nine-arches-bridge', 'Nine Arches Bridge',
     'A colonial-era viaduct built without steel, standing in jungle above the town.',
     6.876700, 81.060200, 90, true, NULL, true, 94, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-00000000000b', 'd0000000-0000-4000-8000-000000000004',
     'little-adams-peak', 'Little Adam''s Peak',
     'The easiest big view in the hill country — about 45 minutes up, mostly steps.',
     6.867600, 81.055300, 120, true, NULL, true, 88, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-00000000000c', 'd0000000-0000-4000-8000-000000000004',
     'ravana-falls', 'Ravana Falls',
     'A 25 m waterfall directly beside the Ella–Wellawaya road. Busiest at midday.',
     6.814400, 81.046400, 45, true, NULL, true, 68, 'PUBLISHED'),

    -- Sigiriya
    ('a0000000-0000-4000-8000-00000000000d', 'd0000000-0000-4000-8000-000000000005',
     'sigiriya-rock', 'Sigiriya Rock Fortress',
     'Water gardens, frescoes, the mirror wall, and a palace on the summit. 1,200 steps.',
     7.957000, 80.760300, 180, false, 10000.00, false, 97, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-00000000000e', 'd0000000-0000-4000-8000-000000000005',
     'pidurangala-rock', 'Pidurangala Rock',
     'The cheaper climb opposite Sigiriya, and the only place to photograph the rock itself.',
     7.968600, 80.759400, 120, false, 1000.00, false, 84, 'PUBLISHED'),

    -- Anuradhapura
    ('a0000000-0000-4000-8000-00000000000f', 'd0000000-0000-4000-8000-000000000006',
     'ruwanwelisaya', 'Ruwanwelisaya Stupa',
     'A white dome 103 m high, ringed by stone elephants. Dress code applies.',
     8.349600, 80.396300, 60, false, 8500.00, false, 66, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-000000000010', 'd0000000-0000-4000-8000-000000000006',
     'sri-maha-bodhi', 'Jaya Sri Maha Bodhi',
     'Grown from a cutting of the original Bodhi tree, and tended continuously since 288 BC.',
     8.344700, 80.396300, 45, false, 8500.00, false, 69, 'PUBLISHED'),

    -- Yala
    ('a0000000-0000-4000-8000-000000000011', 'd0000000-0000-4000-8000-000000000007',
     'yala-block-1-safari', 'Yala Block 1 Safari',
     'The half-day jeep safari through the highest-density leopard territory in the park.',
     6.372800, 81.501600, 240, false, 6000.00, false, 91, 'PUBLISHED'),

    -- Mirissa
    ('a0000000-0000-4000-8000-000000000012', 'd0000000-0000-4000-8000-000000000008',
     'mirissa-beach', 'Mirissa Beach',
     'A sheltered crescent bay, swimmable most of the year, with the palm hill at one end.',
     5.944700, 80.458600, 180, true, NULL, true, 83, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-000000000013', 'd0000000-0000-4000-8000-000000000008',
     'coconut-tree-hill', 'Coconut Tree Hill',
     'A palm-covered headland above the sea. Ten minutes from the beach, best at sunrise.',
     5.944000, 80.449700, 45, true, NULL, true, 76, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-000000000014', 'd0000000-0000-4000-8000-000000000008',
     'whale-watching', 'Blue Whale Watching',
     'Boats leave at 06:30 in season, November to April. Five hours, and often rough.',
     5.946000, 80.452000, 300, false, 12000.00, false, 90, 'PUBLISHED'),

    -- Galle
    ('a0000000-0000-4000-8000-000000000015', 'd0000000-0000-4000-8000-000000000009',
     'galle-fort', 'Galle Fort',
     'A lived-in Dutch fort — ramparts, churches, warehouses and a grid of shaded streets.',
     6.026900, 80.217000, 180, true, NULL, true, 93, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-000000000016', 'd0000000-0000-4000-8000-000000000009',
     'galle-lighthouse', 'Galle Lighthouse',
     'The oldest lighthouse in the country, on the southern rampart of the fort.',
     6.026100, 80.217800, 30, true, NULL, true, 62, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-000000000017', 'd0000000-0000-4000-8000-000000000009',
     'jungle-beach', 'Jungle Beach, Unawatuna',
     'A small snorkelling cove reached through forest below the Japanese peace pagoda.',
     6.012800, 80.241800, 120, true, NULL, true, 61, 'PUBLISHED'),

    -- Arugam Bay
    ('a0000000-0000-4000-8000-000000000018', 'd0000000-0000-4000-8000-00000000000a',
     'main-point', 'Main Point',
     'The long right-hand point break the town is built around. Season is May to September.',
     6.839500, 81.836700, 180, true, NULL, true, 78, 'PUBLISHED'),

    ('a0000000-0000-4000-8000-000000000019', 'd0000000-0000-4000-8000-00000000000a',
     'elephant-rock', 'Elephant Rock',
     'A quieter break under a headland, 20 minutes south. Walk the last stretch along the sand.',
     6.812700, 81.829000, 120, true, NULL, true, 64, 'PUBLISHED')

ON CONFLICT (id) DO UPDATE SET
    destination_id         = EXCLUDED.destination_id,
    slug                   = EXCLUDED.slug,
    name                   = EXCLUDED.name,
    summary                = EXCLUDED.summary,
    latitude               = EXCLUDED.latitude,
    longitude              = EXCLUDED.longitude,
    visit_duration_minutes = EXCLUDED.visit_duration_minutes,
    is_free                = EXCLUDED.is_free,
    entrance_fee           = EXCLUDED.entrance_fee,
    always_open            = EXCLUDED.always_open,
    popularity_score       = EXCLUDED.popularity_score,
    status                 = EXCLUDED.status,
    updated_at             = now();


-- ── Attraction categories ───────────────────────────────────────────────────
DELETE FROM attraction_categories
 WHERE attraction_id IN (SELECT id FROM attractions WHERE id::text LIKE 'a0000000-%');

INSERT INTO attraction_categories (attraction_id, category_code) VALUES
    ('a0000000-0000-4000-8000-000000000001', 'CULTURE'),
    ('a0000000-0000-4000-8000-000000000002', 'CULTURE'),
    ('a0000000-0000-4000-8000-000000000003', 'HISTORY'),

    ('a0000000-0000-4000-8000-000000000004', 'CULTURE'),
    ('a0000000-0000-4000-8000-000000000004', 'HISTORY'),
    ('a0000000-0000-4000-8000-000000000005', 'NATURE'),
    ('a0000000-0000-4000-8000-000000000006', 'NATURE'),

    ('a0000000-0000-4000-8000-000000000007', 'NATURE'),
    ('a0000000-0000-4000-8000-000000000008', 'CULTURE'),
    ('a0000000-0000-4000-8000-000000000008', 'NATURE'),
    ('a0000000-0000-4000-8000-000000000009', 'HIKING'),
    ('a0000000-0000-4000-8000-000000000009', 'NATURE'),

    ('a0000000-0000-4000-8000-00000000000a', 'NATURE'),
    ('a0000000-0000-4000-8000-00000000000b', 'HIKING'),
    ('a0000000-0000-4000-8000-00000000000b', 'NATURE'),
    ('a0000000-0000-4000-8000-00000000000c', 'NATURE'),

    ('a0000000-0000-4000-8000-00000000000d', 'HISTORY'),
    ('a0000000-0000-4000-8000-00000000000d', 'HIKING'),
    ('a0000000-0000-4000-8000-00000000000e', 'HIKING'),
    ('a0000000-0000-4000-8000-00000000000e', 'NATURE'),

    ('a0000000-0000-4000-8000-00000000000f', 'HISTORY'),
    ('a0000000-0000-4000-8000-00000000000f', 'CULTURE'),
    ('a0000000-0000-4000-8000-000000000010', 'CULTURE'),

    ('a0000000-0000-4000-8000-000000000011', 'WILDLIFE'),

    ('a0000000-0000-4000-8000-000000000012', 'BEACH'),
    ('a0000000-0000-4000-8000-000000000013', 'NATURE'),
    ('a0000000-0000-4000-8000-000000000014', 'WILDLIFE'),

    ('a0000000-0000-4000-8000-000000000015', 'HISTORY'),
    ('a0000000-0000-4000-8000-000000000015', 'CULTURE'),
    ('a0000000-0000-4000-8000-000000000016', 'HISTORY'),
    ('a0000000-0000-4000-8000-000000000017', 'BEACH'),

    ('a0000000-0000-4000-8000-000000000018', 'BEACH'),
    ('a0000000-0000-4000-8000-000000000018', 'ADVENTURE'),
    ('a0000000-0000-4000-8000-000000000019', 'BEACH'),
    ('a0000000-0000-4000-8000-000000000019', 'ADVENTURE');
