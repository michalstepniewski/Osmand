package net.osmand.router;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.logging.Log;

import net.osmand.PlatformUtil;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteTypeRule;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.router.BinaryRoutePlanner.FinalRouteSegment;
import net.osmand.router.BinaryRoutePlanner.RouteSegment;
import net.osmand.router.RoutePlannerFrontEnd.RouteCalculationMode;
import net.osmand.util.MapUtils;

public class RouteResultPreparation {

	public static boolean PRINT_TO_CONSOLE_ROUTE_INFORMATION_TO_TEST = false;
	private static final float TURN_DEGREE_MIN = 45;
	private Log log = PlatformUtil.getLog(RouteResultPreparation.class);
	/**
	 * Helper method to prepare final result 
	 */
	List<RouteSegmentResult> prepareResult(RoutingContext ctx, FinalRouteSegment finalSegment) throws IOException {
		List<RouteSegmentResult> result  = convertFinalSegmentToResults(ctx, finalSegment);
		prepareResult(ctx, result);
		return result;
	}

	List<RouteSegmentResult> prepareResult(RoutingContext ctx, List<RouteSegmentResult> result) throws IOException {
		validateAllPointsConnected(result);
		splitRoadsAndAttachRoadSegments(ctx, result);
		// calculate time
		calculateTimeSpeed(ctx, result);
		
		addTurnInfo(ctx.leftSideNavigation, result);
		return result;
	}

	private void calculateTimeSpeed(RoutingContext ctx, List<RouteSegmentResult> result) throws IOException {
		for (int i = 0; i < result.size(); i++) {
			RouteSegmentResult rr = result.get(i);
			RouteDataObject road = rr.getObject();
			double distOnRoadToPass = 0;
			double speed = ctx.getRouter().defineVehicleSpeed(road);
			if (speed == 0) {
				speed = ctx.getRouter().getMinDefaultSpeed();
			} else {
				if(speed > 15) {
					// decrease speed proportionally from 15ms=50kmh - 
					// reference speed 30ms=108kmh - 7kmh
					speed = speed - ((speed - 15f) / (30f - 15f) * 2f);
				}
			}
			boolean plus = rr.getStartPointIndex() < rr.getEndPointIndex();
			int next;
			double distance = 0;
			for (int j = rr.getStartPointIndex(); j != rr.getEndPointIndex(); j = next) {
				next = plus ? j + 1 : j - 1;
				double d = measuredDist(road.getPoint31XTile(j), road.getPoint31YTile(j), road.getPoint31XTile(next),
						road.getPoint31YTile(next));
				distance += d;
				double obstacle = ctx.getRouter().defineObstacle(road, j);
				if (obstacle < 0) {
					obstacle = 0;
				}
				distOnRoadToPass += d / speed + obstacle;

			}
			// last point turn time can be added
			// if(i + 1 < result.size()) { distOnRoadToPass += ctx.getRouter().calculateTurnTime(); }
			rr.setSegmentTime((float) distOnRoadToPass);
			rr.setSegmentSpeed((float) speed);
			rr.setDistance((float) distance);
		}
	}

	private void splitRoadsAndAttachRoadSegments(RoutingContext ctx, List<RouteSegmentResult> result) throws IOException {
		for (int i = 0; i < result.size(); i++) {
			if (ctx.checkIfMemoryLimitCritical(ctx.config.memoryLimitation)) {
				ctx.unloadUnusedTiles(ctx.config.memoryLimitation);
			}
			RouteSegmentResult rr = result.get(i);
			RouteDataObject road = rr.getObject();
			checkAndInitRouteRegion(ctx, road);
			boolean plus = rr.getStartPointIndex() < rr.getEndPointIndex();
			int next;
			for (int j = rr.getStartPointIndex(); j != rr.getEndPointIndex(); j = next) {
				next = plus ? j + 1 : j - 1;
				if (j == rr.getStartPointIndex()) {
					attachRoadSegments(ctx, result, i, j, plus);
				}
				if (next != rr.getEndPointIndex()) {
					attachRoadSegments(ctx, result, i, next, plus);
				}
				List<RouteSegmentResult> attachedRoutes = rr.getAttachedRoutes(next);
				boolean tryToSplit = next != rr.getEndPointIndex() && !rr.getObject().roundabout() && attachedRoutes != null;
				if(rr.getDistance(next, plus ) == 0) {
					// same point will be processed next step
					tryToSplit = false;
				}
				if (tryToSplit) {
					// avoid small zigzags
					float before = rr.getBearing(next, !plus);
					float after = rr.getBearing(next, plus);
					if(rr.getDistance(next, plus ) < 5) {
						after = before + 180;
					} else if(rr.getDistance(next, !plus ) < 5) {
						before = after - 180;
					}
					boolean straight = Math.abs(MapUtils.degreesDiff(before + 180, after)) < TURN_DEGREE_MIN;
					boolean isSplit = false;
					// split if needed
					for (RouteSegmentResult rs : attachedRoutes) {
						double diff = MapUtils.degreesDiff(before + 180, rs.getBearingBegin());
						if (Math.abs(diff) <= TURN_DEGREE_MIN) {
							isSplit = true;
						} else if (!straight && Math.abs(diff) < 100) {
							isSplit = true;
						}
					}
					if (isSplit) {
						int endPointIndex = rr.getEndPointIndex();
						RouteSegmentResult split = new RouteSegmentResult(rr.getObject(), next, endPointIndex);
						split.copyPreattachedRoutes(rr, Math.abs(next - rr.getStartPointIndex()));
						rr.setEndPointIndex(next);
						result.add(i + 1, split);
						i++;
						// switch current segment to the splitted
						rr = split;
					}
				}
			}
		}
	}

	private void checkAndInitRouteRegion(RoutingContext ctx, RouteDataObject road) throws IOException {
		BinaryMapIndexReader reader = ctx.reverseMap.get(road.region);
		if(reader != null) {
			reader.initRouteRegion(road.region);
		}
	}

	private void validateAllPointsConnected(List<RouteSegmentResult> result) {
		for (int i = 1; i < result.size(); i++) {
			RouteSegmentResult rr = result.get(i);
			RouteSegmentResult pr = result.get(i - 1);
			double d = MapUtils.getDistance(pr.getPoint(pr.getEndPointIndex()), rr.getPoint(rr.getStartPointIndex()));
			if (d > 0) {
				System.err.println("Points are not connected : " + pr.getObject() + "(" + pr.getEndPointIndex() + ") -> " + rr.getObject()
						+ "(" + rr.getStartPointIndex() + ") " + d + " meters");
			}
		}
	}

	private List<RouteSegmentResult> convertFinalSegmentToResults(RoutingContext ctx, FinalRouteSegment finalSegment) {
		List<RouteSegmentResult> result = new ArrayList<RouteSegmentResult>();
		if (finalSegment != null) {
			ctx.routingTime = finalSegment.distanceFromStart;
			println("Routing calculated time distance " + finalSegment.distanceFromStart);
			// Get results from opposite direction roads
			RouteSegment segment = finalSegment.reverseWaySearch ? finalSegment : 
				finalSegment.opposite.getParentRoute();
			int parentSegmentStart = finalSegment.reverseWaySearch ? finalSegment.opposite.getSegmentStart() : 
				finalSegment.opposite.getParentSegmentEnd();
			float parentRoutingTime = -1;
			while (segment != null) {
				RouteSegmentResult res = new RouteSegmentResult(segment.road, parentSegmentStart, segment.getSegmentStart());
				parentRoutingTime = calcRoutingTime(parentRoutingTime, finalSegment, segment, res);
				parentSegmentStart = segment.getParentSegmentEnd();
				segment = segment.getParentRoute();
				addRouteSegmentToResult(ctx, result, res, false);
			}
			// reverse it just to attach good direction roads
			Collections.reverse(result);

			segment = finalSegment.reverseWaySearch ? finalSegment.opposite.getParentRoute() : finalSegment;
			int parentSegmentEnd = finalSegment.reverseWaySearch ? finalSegment.opposite.getParentSegmentEnd() : finalSegment.opposite.getSegmentStart();
			parentRoutingTime = -1;
			while (segment != null) {
				RouteSegmentResult res = new RouteSegmentResult(segment.road, segment.getSegmentStart(), parentSegmentEnd);
				parentRoutingTime = calcRoutingTime(parentRoutingTime, finalSegment, segment, res);
				parentSegmentEnd = segment.getParentSegmentEnd();
				segment = segment.getParentRoute();
				// happens in smart recalculation
				addRouteSegmentToResult(ctx, result, res, true);
			}
			Collections.reverse(result);
			// checkTotalRoutingTime(result);
		}
		return result;
	}

	protected void checkTotalRoutingTime(List<RouteSegmentResult> result) {
		float totalRoutingTime = 0;
		for(RouteSegmentResult r : result) {
			totalRoutingTime += r.getRoutingTime();
		}
		println("Total routing time ! " + totalRoutingTime);
	}

	private float calcRoutingTime(float parentRoutingTime, RouteSegment finalSegment, RouteSegment segment,
			RouteSegmentResult res) {
		if(segment != finalSegment) {
			if(parentRoutingTime != -1) {
				res.setRoutingTime(parentRoutingTime - segment.distanceFromStart);
			}
			parentRoutingTime = segment.distanceFromStart;
		}
		return parentRoutingTime;
	}
	
	private void addRouteSegmentToResult(RoutingContext ctx, List<RouteSegmentResult> result, RouteSegmentResult res, boolean reverse) {
		if (res.getStartPointIndex() != res.getEndPointIndex()) {
			if (result.size() > 0) {
				RouteSegmentResult last = result.get(result.size() - 1);
				if (last.getObject().id == res.getObject().id && ctx.calculationMode != RouteCalculationMode.BASE) {
					if (combineTwoSegmentResult(res, last, reverse)) {
						return;
					}
				}
			}
			result.add(res);
		}
	}
	
	private boolean combineTwoSegmentResult(RouteSegmentResult toAdd, RouteSegmentResult previous, 
			boolean reverse) {
		boolean ld = previous.getEndPointIndex() > previous.getStartPointIndex();
		boolean rd = toAdd.getEndPointIndex() > toAdd.getStartPointIndex();
		if (rd == ld) {
			if (toAdd.getStartPointIndex() == previous.getEndPointIndex() && !reverse) {
				previous.setEndPointIndex(toAdd.getEndPointIndex());
				previous.setRoutingTime(previous.getRoutingTime() + toAdd.getRoutingTime());
				return true;
			} else if (toAdd.getEndPointIndex() == previous.getStartPointIndex() && reverse) {
				previous.setStartPointIndex(toAdd.getStartPointIndex());
				previous.setRoutingTime(previous.getRoutingTime() + toAdd.getRoutingTime());
				return true;
			}
		}
		return false;
	}
	
	void printResults(RoutingContext ctx, LatLon start, LatLon end, List<RouteSegmentResult> result) {
		float completeTime = 0;
		float completeDistance = 0;
		for(RouteSegmentResult r : result) {
			completeTime += r.getSegmentTime();
			completeDistance += r.getDistance();
		}

		println("ROUTE : ");
		double startLat = start.getLatitude();
		double startLon = start.getLongitude();
		double endLat = end.getLatitude();
		double endLon = end.getLongitude();
		String msg = MessageFormat.format("<test regions=\"\" description=\"\" best_percent=\"\" vehicle=\"{4}\" \n"
				+ "    start_lat=\"{0}\" start_lon=\"{1}\" target_lat=\"{2}\" target_lon=\"{3}\" {5} >", 
				startLat + "", startLon + "", endLat + "", endLon + "", ctx.config.routerName, 
				"loadedTiles = \"" + ctx.loadedTiles + "\" " + "visitedSegments = \"" + ctx.visitedSegments + "\" " +
				"complete_distance = \"" + completeDistance + "\" " + "complete_time = \"" + completeTime + "\" " +
				"routing_time = \"" + ctx.routingTime + "\" ");
		log.info(msg);
        println(msg);
		if (PRINT_TO_CONSOLE_ROUTE_INFORMATION_TO_TEST) {
			for (RouteSegmentResult res : result) {
				String name = res.getObject().getName();
				String ref = res.getObject().getRef();
				if (name == null) {
					name = "";
				}
				if (ref != null) {
					name += " (" + ref + ") ";
				}
				StringBuilder additional = new StringBuilder();
				additional.append("time = \"").append(res.getSegmentTime()).append("\" ");
				additional.append("rtime = \"").append(res.getRoutingTime()).append("\" ");
				additional.append("name = \"").append(name).append("\" ");
//				float ms = res.getSegmentSpeed();
				float ms = res.getObject().getMaximumSpeed();
				if(ms > 0) {
					additional.append("maxspeed = \"").append(ms * 3.6f).append("\" ").append(res.getObject().getHighway()).append(" ");
				}
				additional.append("distance = \"").append(res.getDistance()).append("\" ");
				if (res.getTurnType() != null) {
					additional.append("turn = \"").append(res.getTurnType()).append("\" ");
					additional.append("turn_angle = \"").append(res.getTurnType().getTurnAngle()).append("\" ");
					if (res.getTurnType().getLanes() != null) {
						additional.append("lanes = \"").append(Arrays.toString(res.getTurnType().getLanes())).append("\" ");
					}
				}
				additional.append("start_bearing = \"").append(res.getBearingBegin()).append("\" ");
				additional.append("end_bearing = \"").append(res.getBearingEnd()).append("\" ");
				additional.append("description = \"").append(res.getDescription()).append("\" ");
				println(MessageFormat.format("\t<segment id=\"{0}\" start=\"{1}\" end=\"{2}\" {3}/>", (res.getObject().getId()) + "",
						res.getStartPointIndex() + "", res.getEndPointIndex() + "", additional.toString()));
				printAdditionalPointInfo(res);
			}
		}
		println("</test>");
	}

	private void printAdditionalPointInfo(RouteSegmentResult res) {
		boolean plus = res.getStartPointIndex() < res.getEndPointIndex();
		for(int k = res.getStartPointIndex(); k != res.getEndPointIndex(); ) {
			int[] tp = res.getObject().getPointTypes(k);
			if(tp != null) {
				for(int t = 0; t < tp.length; t++) {
					RouteTypeRule rr = res.getObject().region.quickGetEncodingRule(tp[t]);
					println("\t<point tag=\""+rr.getTag()+"\"" + " value=\""+rr.getValue()+"\"/>");
				}
			}
			if(plus) {
				k++;
			} else {
				k--;
			}
		}
	}


	private void addTurnInfo(boolean leftside, List<RouteSegmentResult> result) {
		int prevSegment = -1;
		float dist = 0;
		int next = 1;
		for (int i = 0; i <= result.size(); i = next) {
			TurnType t = null;
			next = i + 1;
			if (i < result.size()) {
				t = getTurnInfo(result, i, leftside);
				// justify turn
				if(t != null && i < result.size() - 1) {
					boolean tl = TurnType.TL == t.getValue();
					boolean tr = TurnType.TR == t.getValue();
					if(tl || tr) {
						TurnType tnext = getTurnInfo(result, i + 1, leftside);
						if (tnext != null && result.get(i).getDistance() < 35) { //
							boolean ut = true;
							if (i > 0) {
								double uTurn = MapUtils.degreesDiff(result.get(i - 1).getBearingEnd(), result
										.get(i + 1).getBearingBegin());
								if (Math.abs(uTurn) < 120) {
									ut = false;
								}
							}
							String highway = result.get(i).getObject().getHighway();
							if(highway == null || highway.endsWith("track") || highway.endsWith("services") || highway.endsWith("service")
									|| highway.endsWith("path")) {
								ut = false;
							}
							if (ut) {
								if (tl && TurnType.TL == tnext.getValue()) {
									next = i + 2;
									t = TurnType.valueOf(TurnType.TU, false);
								} else if (tr && TurnType.TR == tnext.getValue()) {
									next = i + 2;
									t = TurnType.valueOf(TurnType.TU, true);
								}
							}
						}
					}
				}
				result.get(i).setTurnType(t);
			}
			if (t != null || i == result.size()) {
				if (prevSegment >= 0) {
					String turn = result.get(prevSegment).getTurnType().toString();
					if (result.get(prevSegment).getTurnType().getLanes() != null) {
						turn += Arrays.toString(result.get(prevSegment).getTurnType().getLanes());
					}
					result.get(prevSegment).setDescription(turn + MessageFormat.format(" and go {0,number,#.##} meters", dist));
					if(result.get(prevSegment).getTurnType().isSkipToSpeak()) {
						result.get(prevSegment).setDescription("-*"+result.get(prevSegment).getDescription());
					}
				}
				prevSegment = i;
				dist = 0;
			}
			if (i < result.size()) {
				dist += result.get(i).getDistance();
			}
		}
	}
	
	private static final int MAX_SPEAK_PRIORITY = 5;
	private int highwaySpeakPriority(String highway) {
		if(highway == null || highway.endsWith("track") || highway.endsWith("services") || highway.endsWith("service")
				|| highway.endsWith("path")) {
			return MAX_SPEAK_PRIORITY;
		}
		if (highway.endsWith("_link")  || highway.endsWith("unclassified") || highway.endsWith("road") 
				|| highway.endsWith("living_street") || highway.endsWith("residential") )  {
			return 1;
		}
		return 0;
	}


	private TurnType getTurnInfo(List<RouteSegmentResult> result, int i, boolean leftSide) {
		if (i == 0) {
			return TurnType.valueOf(TurnType.C, false);
		}
		RouteSegmentResult prev = result.get(i - 1) ;
		if(prev.getObject().roundabout()) {
			// already analyzed!
			return null;
		}
		RouteSegmentResult rr = result.get(i);
		if (rr.getObject().roundabout()) {
			return processRoundaboutTurn(result, i, leftSide, prev, rr);
		}
		TurnType t = null;
		if (prev != null) {
			boolean noAttachedRoads = rr.getAttachedRoutes(rr.getStartPointIndex()).size() == 0;
			// add description about turn
			double mpi = MapUtils.degreesDiff(prev.getBearingEnd(), rr.getBearingBegin());
			if(noAttachedRoads){
				// TODO VICTOR : look at the comment inside direction route
				// ? avoid small zigzags is covered at (search for "zigzags") 
//				double begin = rr.getObject().directionRoute(rr.getStartPointIndex(), rr.getStartPointIndex() < 
//						rr.getEndPointIndex(), 25);
//				mpi = MapUtils.degreesDiff(prev.getBearingEnd(), begin);
			}
			if (mpi >= TURN_DEGREE_MIN) {
				if (mpi < 60) {
					t = TurnType.valueOf(TurnType.TSLL, leftSide);
				} else if (mpi < 120) {
					t = TurnType.valueOf(TurnType.TL, leftSide);
				} else if (mpi < 135 || leftSide) {
					t = TurnType.valueOf(TurnType.TSHL, leftSide);
				} else {
					t = TurnType.valueOf(TurnType.TU, leftSide);
				}
				assignLanesInfo(prev, t, leftSide);
			} else if (mpi < -TURN_DEGREE_MIN) {
				if (mpi > -60) {
					t = TurnType.valueOf(TurnType.TSLR, leftSide);
				} else if (mpi > -120) {
					t = TurnType.valueOf(TurnType.TR, leftSide);
				} else if (mpi > -135 || !leftSide) {
					t = TurnType.valueOf(TurnType.TSHR, leftSide);
				} else {
					t = TurnType.valueOf(TurnType.TU, leftSide);
				}
				assignLanesInfo(prev, t, leftSide);
			} else {
				t = attachKeepLeftInfoAndLanes(leftSide, prev, rr, t);
			}
			if (t != null) {
				t.setTurnAngle((float) -mpi);
			}
		}
		return t;
	}

	private void assignLanesInfo(RouteSegmentResult prevSegm, TurnType t, boolean leftSide) {
		int lanes = countLanes(prevSegm);
		if (lanes <= 0) {
			return;
		}

		String turnLanes = getTurnLanesString(prevSegm);
		if (turnLanes == null) {
			return;
		}

		String[] splitLaneOptions = turnLanes.split("\\|", -1);
		if (splitLaneOptions.length != lanes) {
			// Error in data or missing data
			return;
		}

		int[] lanesArray = new int[lanes];
		t.setLanes(lanesArray);
		assignTurns(splitLaneOptions, t);

		// In some cases (at least in the US), the rightmost lane might not have a right turn indicated as per turn:lanes, but is allowed and being used here. This section adds in that indicator.  The same applies for where leftSide is true.
		if (leftSide) {
			if (t.getValue() == TurnType.TL
					&& TurnType.getPrimaryTurn(lanesArray[0]) != TurnType.TL
					&& TurnType.getPrimaryTurn(lanesArray[0]) != TurnType.TSLL
					&& TurnType.getPrimaryTurn(lanesArray[0]) != TurnType.TSHL) {
				if (TurnType.getPrimaryTurn(lanesArray[0]) != 0) {
					// This was just to make sure that there's no bad data.
					t.setSecondaryTurn(0, TurnType.getPrimaryTurn(lanesArray[0]));
					t.setPrimaryTurn(0, TurnType.TL);
				}
			}
		} else {
			int lastIndex = lanesArray.length - 1;
			if (t.getValue() == TurnType.TR
					&& TurnType.getPrimaryTurn(lanesArray[lastIndex]) != TurnType.TR
					&& TurnType.getPrimaryTurn(lanesArray[lastIndex]) != TurnType.TSLR
					&& TurnType.getPrimaryTurn(lanesArray[lastIndex]) != TurnType.TSHR) {
				if (TurnType.getPrimaryTurn(lanesArray[lastIndex]) != 0) {
					// This was just to make sure that there's no bad data.
					t.setSecondaryTurn(lastIndex, TurnType.getPrimaryTurn(lanesArray[lastIndex]));
					t.setPrimaryTurn(lastIndex, TurnType.TR);
				}
			}
		}

		// Manually set the allowed lanes.
		for (int i = 0; i < lanesArray.length; i++) {
			if (TurnType.getPrimaryTurn(lanesArray[i]) == t.getValue()) {
				lanesArray[i] |= 1;
			}
		}
	}

	private TurnType processRoundaboutTurn(List<RouteSegmentResult> result, int i, boolean leftSide, RouteSegmentResult prev,
			RouteSegmentResult rr) {
		int exit = 1;
		RouteSegmentResult last = rr;
		for (int j = i; j < result.size(); j++) {
			RouteSegmentResult rnext = result.get(j);
			last = rnext;
			if (rnext.getObject().roundabout()) {
				boolean plus = rnext.getStartPointIndex() < rnext.getEndPointIndex();
				int k = rnext.getStartPointIndex();
				if (j == i) {
					// first exit could be immediately after roundabout enter
//					k = plus ? k + 1 : k - 1;
				}
				while (k != rnext.getEndPointIndex()) {
					int attachedRoads = rnext.getAttachedRoutes(k).size();
					if(attachedRoads > 0) {
						exit++;
					}
					k = plus ? k + 1 : k - 1;
				}
			} else {
				break;
			}
		}
		// combine all roundabouts
		TurnType t = TurnType.getExitTurn(exit, 0, leftSide);
		t.setTurnAngle((float) MapUtils.degreesDiff(last.getBearingBegin(), prev.getBearingEnd())) ;
		return t;
	}


	private TurnType attachKeepLeftInfoAndLanes(boolean leftSide, RouteSegmentResult prevSegm, RouteSegmentResult currentSegm, TurnType t) {
		// keep left/right
		int[] lanes =  null;
		boolean kl = false;
		boolean kr = false;
		List<RouteSegmentResult> attachedRoutes = currentSegm.getAttachedRoutes(currentSegm.getStartPointIndex());
		int ls = prevSegm.getObject().getLanes();
		if(ls >= 0 && prevSegm.getObject().getOneway() == 0) {
			ls = (ls + 1) / 2;
		}
		int left = 0;
		int right = 0;
		boolean speak = false;
		int speakPriority = Math.max(highwaySpeakPriority(prevSegm.getObject().getHighway()), highwaySpeakPriority(currentSegm.getObject().getHighway()));
		if (attachedRoutes != null) {
			for (RouteSegmentResult attached : attachedRoutes) {
				double ex = MapUtils.degreesDiff(attached.getBearingBegin(), currentSegm.getBearingBegin());
				double mpi = Math.abs(MapUtils.degreesDiff(prevSegm.getBearingEnd(), attached.getBearingBegin()));
				int rsSpeakPriority = highwaySpeakPriority(attached.getObject().getHighway());
				if (rsSpeakPriority != MAX_SPEAK_PRIORITY || speakPriority == MAX_SPEAK_PRIORITY) {
					if ((ex < TURN_DEGREE_MIN || mpi < TURN_DEGREE_MIN) && ex >= 0) {
						kl = true;
						int lns = countLanes(attached);
						if (lns <= 0) {
							right += 1;
						} else {
							right += lns;
						}
						speak = speak || rsSpeakPriority <= speakPriority;
					} else if ((ex > -TURN_DEGREE_MIN || mpi < TURN_DEGREE_MIN) && ex <= 0) {
						kr = true;
						int lns = countLanes(attached);
						if (lns <= 0) {
							left += 1;
						} else {
							left += lns;
						}
						speak = speak || rsSpeakPriority <= speakPriority;
					}
				}
			}
		}
		if(kr && left == 0) {
			left = 1;
		} else if(kl && right == 0) {
			right = 1;
		}
		int current = countLanes(currentSegm);
		// attachedRoutes covers all allowed outbound routes at that point except currentSegm.
		if (current <= 0) {
			current = 1;
		}
//		if(ls >= 0 /*&& current + left + right >= ls*/){
			lanes = new int[current + left + right];
			ls = current + left + right;
			for(int it=0; it< ls; it++) {
				if(it < left || it >= left + current) {
					lanes[it] = 0;
				} else {
					lanes[it] = 1;
				}
			}
			// sometimes links are 
			if ((current <= left + right) && (left > 1 || right > 1)) {
				speak = true;
			}
//		}

		double devation = Math.abs(MapUtils.degreesDiff(prevSegm.getBearingEnd(), currentSegm.getBearingBegin()));
		boolean makeSlightTurn = devation > 5 && (!isMotorway(prevSegm) || !isMotorway(currentSegm));
		if (kl) {
			t = TurnType.valueOf(makeSlightTurn ? TurnType.TSLL : TurnType.KL, leftSide);
			t.setSkipToSpeak(!speak);
		} 
		if (kr) {
			t = TurnType.valueOf(makeSlightTurn ? TurnType.TSLR : TurnType.KR, leftSide);
			t.setSkipToSpeak(!speak);
		}
		if (t != null && lanes != null) {
			t.setLanes(lanes);

			t = attachTurnLanesData(leftSide, prevSegm, t);
		}
		return t;
	}

	protected int countLanes(RouteSegmentResult attached) {
		if (attached.getObject().getOneway() == 0) {
			try {
				if (attached.isForwardDirection() && attached.getObject().getValue("lanes:forward") != null) {
					return Integer.parseInt(attached.getObject().getValue("lanes:forward"));
				} else if (!attached.isForwardDirection() && attached.getObject().getValue("lanes:backward") != null) {
					return Integer.parseInt(attached.getObject().getValue("lanes:backward"));
				} else {
					return -1;
				}
			} catch (NumberFormatException e) {
				e.printStackTrace();
				return -1;
			}
		} else {
			return attached.getObject().getLanes();
		}
	}

	protected String getTurnLanesString(RouteSegmentResult segment) {
		if (segment.getObject().getOneway() == 0) {
			if (segment.isForwardDirection()) {
				return segment.getObject().getValue("turn:lanes:forward");
			} else {
				return segment.getObject().getValue("turn:lanes:backward");
			}
		} else {
			return segment.getObject().getValue("turn:lanes");
		}
	}

	private TurnType attachTurnLanesData(boolean leftSide, RouteSegmentResult prevSegm, TurnType t) {
		int lanes = countLanes(prevSegm);
		String turnLanes = getTurnLanesString(prevSegm);

		if (turnLanes == null) {
			return t;
		}

		String[] splitLaneOptions = turnLanes.split("\\|", -1);

		if (t.getLanes().length != lanes) {
			// The lanes from prevSegm don't easily match up to the target roads.
			List<Integer> sourceLanes = new ArrayList<Integer>();

			int outgoingLanesIndex = 0;
			int sourceLanesIndex = 0;

			while (outgoingLanesIndex < t.getLanes().length && sourceLanesIndex < lanes) {
				if (splitLaneOptions[sourceLanesIndex].contains(";")) {
					// Two or more allowed turns for this lane
					int options = countOccurrences(splitLaneOptions[sourceLanesIndex], ';');
					if (options == 1) {
						if (outgoingLanesIndex + 1 >= t.getLanes().length) {
							// Likely an error in data
							return t;
						}
						int usability = t.getLanes()[outgoingLanesIndex] | t.getLanes()[outgoingLanesIndex + 1];
						sourceLanes.add(usability);
						outgoingLanesIndex += 2;
						sourceLanesIndex++;
					} else {
						// Not supported
						return t;
					}
				} else {
					// Only one allowed turn; behave normally
					sourceLanes.add(t.getLanes()[outgoingLanesIndex]);
					outgoingLanesIndex++;
					sourceLanesIndex++;
				}
			}

			int[] newLanes = new int[sourceLanes.size()];

			for (int i = 0; i < sourceLanes.size(); i++) {
				newLanes[i] = sourceLanes.get(i);
			}

			t.setLanes(newLanes);
		}

		assignTurns(splitLaneOptions, t);
		t = inferTurnFromLanes(t, leftSide);
		return t;
	}

	private int countOccurrences(String haystack, char needle) {
	    int count = 0;
		for (int i = 0; i < haystack.length(); i++) {
			if (haystack.charAt(i) == needle) {
				count++;
			}
		}
		return count;
	}

	private void assignTurns(String[] splitLaneOptions, TurnType t) {
		for (int i = 0; i < splitLaneOptions.length; i++) {
			String[] laneOptions = splitLaneOptions[i].split(";");

			for (int j = 0; j < laneOptions.length; j++) {
				int turn;
				if (laneOptions[j].equals("none") || laneOptions[j].equals("through")) {
					turn = TurnType.C;
				} else if (laneOptions[j].equals("slight_right")) {
					turn = TurnType.TSLR;
				} else if (laneOptions[j].equals("slight_left")) {
					turn = TurnType.TSLL;
				} else if (laneOptions[j].equals("right")) {
					turn = TurnType.TR;
				} else if (laneOptions[j].equals("left")) {
					turn = TurnType.TL;
				} else if (laneOptions[j].equals("sharp_right")) {
					turn = TurnType.TSHR;
				} else if (laneOptions[j].equals("sharp_left")) {
					turn = TurnType.TSHL;
				} else if (laneOptions[j].equals("reverse")) {
					turn = TurnType.TU;
				} else {
					// Unknown string
					continue;
				}

				if (TurnType.getPrimaryTurn(t.getLanes()[i]) == 0) {
					t.setPrimaryTurn(i, turn);
				} else {
                    if (turn == t.getValue()) {
                        t.setSecondaryTurn(i, TurnType.getPrimaryTurn(t.getLanes()[i]));
                        t.setPrimaryTurn(i, turn);
                    } else {
                        t.setSecondaryTurn(i, turn);
                    }

					break; // Move on to the next lane
				}
			}
		}
	}

	private TurnType inferTurnFromLanes(TurnType t, boolean leftSide) {
		List<Integer> possibleTurns = new ArrayList<Integer>();
		for (int i = 0; i < t.getLanes().length; i++) {
			if ((t.getLanes()[i] & 1) == 0) {
				continue;
			}

			if (possibleTurns.isEmpty()) {
				// Nothing is in the list to compare to, so add the first elements
				possibleTurns.add(TurnType.getPrimaryTurn(t.getLanes()[i]));
				if (TurnType.getSecondaryTurn(t.getLanes()[i]) != 0) {
					possibleTurns.add(TurnType.getSecondaryTurn(t.getLanes()[i]));
				}
			} else {
				List<Integer> laneTurns = new ArrayList<Integer>();
				laneTurns.add(TurnType.getPrimaryTurn(t.getLanes()[i]));
				if (TurnType.getSecondaryTurn(t.getLanes()[i]) != 0) {
					laneTurns.add(TurnType.getSecondaryTurn(t.getLanes()[i]));
				}
				possibleTurns.retainAll(laneTurns);

				if (possibleTurns.isEmpty()) {
					// No common turns, so can't determine anything.
					return t;
				}
			}
		}

		// Remove all turns from lanes not selected...because those aren't it
		for (int i = 0; i < t.getLanes().length; i++) {
			if ((t.getLanes()[i] & 1) == 0 && !possibleTurns.isEmpty()) {
				List<Integer> notLaneTurns = new ArrayList<Integer>();
				notLaneTurns.add(TurnType.getPrimaryTurn(t.getLanes()[i]));
				if (TurnType.getSecondaryTurn(t.getLanes()[i]) != 0) {
					notLaneTurns.add(TurnType.getSecondaryTurn(t.getLanes()[i]));
				}
				possibleTurns.removeAll(notLaneTurns);
			}
		}

		// Checking to see that there is only one unique turn
		if (new HashSet<Integer>(possibleTurns).size() == 1) {
			TurnType derivedTurnType = TurnType.valueOf(possibleTurns.get(0), leftSide);
			derivedTurnType.setLanes(t.getLanes());
			derivedTurnType.setSkipToSpeak(t.isSkipToSpeak());
			t = derivedTurnType;

			// Because only the primary turn is displayed, if the turn to be taken is currently set as the secondary turn, then that needs to be switched around.
			for (int i = 0; i < t.getLanes().length; i++) {
				if (TurnType.getSecondaryTurn(t.getLanes()[i]) == t.getValue()) {
					int temp = TurnType.getSecondaryTurn(t.getLanes()[i]);
					t.setSecondaryTurn(i, TurnType.getPrimaryTurn(t.getLanes()[i]));
					t.setPrimaryTurn(i, temp);
				}
			}
		}

		return t;
	}

	private boolean isMotorway(RouteSegmentResult s){
		String h = s.getObject().getHighway();
		return "motorway".equals(h) || "motorway_link".equals(h)  ||
				"trunk".equals(h) || "trunk_link".equals(h);
		
	}

	
	private void attachRoadSegments(RoutingContext ctx, List<RouteSegmentResult> result, int routeInd, int pointInd, boolean plus) throws IOException {
		RouteSegmentResult rr = result.get(routeInd);
		RouteDataObject road = rr.getObject();
		long nextL = pointInd < road.getPointsLength() - 1 ? getPoint(road, pointInd + 1) : 0;
		long prevL = pointInd > 0 ? getPoint(road, pointInd - 1) : 0;
		
		// attach additional roads to represent more information about the route
		RouteSegmentResult previousResult = null;
		
		// by default make same as this road id
		long previousRoadId = road.getId();
		if (pointInd == rr.getStartPointIndex() && routeInd > 0) {
			previousResult = result.get(routeInd - 1);
			previousRoadId = previousResult.getObject().getId();
			if (previousRoadId != road.getId()) {
				if (previousResult.getStartPointIndex() < previousResult.getEndPointIndex()
						&& previousResult.getEndPointIndex() < previousResult.getObject().getPointsLength() - 1) {
					rr.attachRoute(pointInd, new RouteSegmentResult(previousResult.getObject(), previousResult.getEndPointIndex(),
							previousResult.getObject().getPointsLength() - 1));
				} else if (previousResult.getStartPointIndex() > previousResult.getEndPointIndex() 
						&& previousResult.getEndPointIndex() > 0) {
					rr.attachRoute(pointInd, new RouteSegmentResult(previousResult.getObject(), previousResult.getEndPointIndex(), 0));
				}
			}
		}
		Iterator<RouteSegment> it;
		if(rr.getPreAttachedRoutes(pointInd) != null) {
			final RouteSegmentResult[] list = rr.getPreAttachedRoutes(pointInd);
			it = new Iterator<BinaryRoutePlanner.RouteSegment>() {
				int i = 0;
				@Override
				public boolean hasNext() {
					return i < list.length;
				}

				@Override
				public RouteSegment next() {
					RouteSegmentResult r = list[i++];
					return new RouteSegment(r.getObject(), r.getStartPointIndex());
				}

				@Override
				public void remove() {
				}
			};	
		} else {
			RouteSegment rt = ctx.loadRouteSegment(road.getPoint31XTile(pointInd), road.getPoint31YTile(pointInd), ctx.config.memoryLimitation);
			it = rt == null ? null : rt.getIterator();
		}
		// try to attach all segments except with current id
		while (it != null && it.hasNext()) {
			RouteSegment routeSegment = it.next();
			if (routeSegment.road.getId() != road.getId() && routeSegment.road.getId() != previousRoadId) {
				RouteDataObject addRoad = routeSegment.road;
				checkAndInitRouteRegion(ctx, addRoad);
				// TODO restrictions can be considered as well
				int oneWay = ctx.getRouter().isOneWay(addRoad);
				if (oneWay >= 0 && routeSegment.getSegmentStart() < addRoad.getPointsLength() - 1) {
					long pointL = getPoint(addRoad, routeSegment.getSegmentStart() + 1);
					if(pointL != nextL && pointL != prevL) {
						// if way contains same segment (nodes) as different way (do not attach it)
						rr.attachRoute(pointInd, new RouteSegmentResult(addRoad, routeSegment.getSegmentStart(), addRoad.getPointsLength() - 1));
					}
				}
				if (oneWay <= 0 && routeSegment.getSegmentStart() > 0) {
					long pointL = getPoint(addRoad, routeSegment.getSegmentStart() - 1);
					// if way contains same segment (nodes) as different way (do not attach it)
					if(pointL != nextL && pointL != prevL) {
						rr.attachRoute(pointInd, new RouteSegmentResult(addRoad, routeSegment.getSegmentStart(), 0));
					}
				}
			}
		}
	}
	
	private static void println(String logMsg) {
//		log.info(logMsg);
		System.out.println(logMsg);
	}
	
	private long getPoint(RouteDataObject road, int pointInd) {
		return (((long) road.getPoint31XTile(pointInd)) << 31) + (long) road.getPoint31YTile(pointInd);
	}
	
	private static double measuredDist(int x1, int y1, int x2, int y2) {
		return MapUtils.getDistance(MapUtils.get31LatitudeY(y1), MapUtils.get31LongitudeX(x1), 
				MapUtils.get31LatitudeY(y2), MapUtils.get31LongitudeX(x2));
	}
}
