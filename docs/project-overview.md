# ExploreLK

## What is ExploreLK?

ExploreLK is a Sri Lankan local travel planning and experience platform that helps travelers discover destinations, plan multi-day trips, build efficient itineraries, and book local experiences through one platform.

Instead of travelers separately researching destinations, figuring out which places fit together, manually organizing each day, and finding activities from different providers, ExploreLK brings the journey together.

The core experience is:

```
Discover
   ↓
Plan
   ↓
Optimize
   ↓
Experience
   ↓
Book
   ↓
Manage Trip
```

### Example

A traveler can provide information such as:

- **Starting point:** Colombo
- **Duration:** 6 days
- **Budget:** LKR 120,000

**Interests:**

- ✓ Nature
- ✓ Hiking
- ✓ Wildlife
- ✓ Beaches

ExploreLK can use this information to create a practical trip:

| Day | Route | Places |
| --- | --- | --- |
| Day 1 | Colombo → Kandy | Temple of the Tooth, Kandy Lake |
| Day 2 | Kandy → Nuwara Eliya | Tea Plantation, Gregory Lake |
| Day 3 | Nuwara Eliya → Ella | Nine Arches Bridge, Little Adam's Peak |
| Day 4 | Ella → Yala | Yala Safari |
| Day 5 | Yala → Mirissa | Beach / Surfing |
| Day 6 | Mirissa → Galle | Galle Fort |

Travelers aren't locked into the generated plan. They can modify destinations and activities, reorganize days, discover experiences available around each destination, and make bookings.

### Local experience providers

ExploreLK also connects local tourism experience providers with travelers. Providers can offer experiences such as:

- Safari tours
- Guided hikes
- Surfing lessons
- Whale watching
- Cooking experiences
- Cultural tours
- Cycling tours
- Tea estate tours
- Local guided experiences

So ExploreLK isn't simply a destination directory or itinerary generator.

It combines:

Sri Lanka destination discovery + intelligent trip planning + itinerary management + local experience discovery + experience booking.

## Vision

To become a unified digital platform for discovering, planning, and experiencing Sri Lanka, making it simple for travelers to turn their interests, time, and budget into practical journeys while connecting them with authentic local experiences.

ExploreLK aims to remove the fragmentation that exists when planning local travel.

Instead of:

- Google → find places
- Maps → figure out distances
- Blogs → decide where to go
- Notes → create itinerary
- Different websites → find activities
- Messages/calls → make reservations

the vision is:

```
                    ExploreLK
                        │
             ┌──────────┴──────────┐
             │                     │
         DISCOVER                PLAN
             │                     │
     Places & attractions    Trip preferences
     Local experiences      Time / budget
             │                     │
             └──────────┬──────────┘
                        ↓
                  BUILD JOURNEY
                        │
                Route optimization
                Day-by-day itinerary
                        ↓
                    EXPERIENCE
                        │
                 Local activities
                 Local providers
                        ↓
                      BOOK
                        │
                Manage reservations
                        ↓
                     TRAVEL
```

The long-term product direction is for ExploreLK to become the traveler's digital companion for exploring Sri Lanka — from the initial question of "Where should I go?" to having an organized, bookable trip ready to experience.

## MVP Services

These are the services we currently have for the MVP:

| # | Service | Responsibility | Scope |
| --- | --- | --- | --- |
| 1 | Auth Service | Authentication and authorization | Users, login, JWT/refresh tokens, roles, traveler/provider/admin accounts |
| 2 | Destination Service | Sri Lankan destination/attraction information | Destinations, attractions, categories, coordinates, opening hours, estimated visit duration |
| 3 | Trip Service | Manage a traveler's trips | Trip dates, starting point, budget, interests, trip status |
| 4 | Itinerary Service | Build and manage day-by-day travel plans | Itinerary days, route ordering, destination selection, itinerary optimization |
| 5 | Experience Service | Manage bookable local activities | Experiences, providers, prices, capacity, schedules/availability |
| 6 | Booking Service | Handle experience reservations | Bookings, capacity reservation, cancellation, booking status, idempotency |
| 7 | Notification Service | Notify users about important events | Booking confirmations/cancellations and stored user notifications |
| + | API Gateway | Entry point to the whole platform | Routing, authentication checks, rate limiting, request handling |
