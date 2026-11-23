import { View } from "react-native";
import Svg, { Circle, Path, Line } from "react-native-svg";
import { T } from "./ui";
import { useTheme } from "../theme/ThemeProvider";
import { clamp } from "../utils/format";
export function ScoreRing({ value }: { value: number }) {
  const c = useTheme(),
    r = 29,
    length = 2 * Math.PI * r;
  return (
    <View
      accessibilityLabel={`Money health ${value} out of 100`}
      style={{
        width: 76,
        height: 76,
        justifyContent: "center",
        alignItems: "center",
      }}
    >
      <Svg width={76} height={76} style={{ position: "absolute" }}>
        <Circle
          cx={38}
          cy={38}
          r={r}
          fill="none"
          stroke={c.line}
          strokeWidth={7}
        />
        <Circle
          cx={38}
          cy={38}
          r={r}
          fill="none"
          stroke={c.greenBright}
          strokeWidth={7}
          strokeDasharray={`${(length * clamp(value)) / 100} ${length}`}
          rotation={-90}
          origin="38,38"
          strokeLinecap="round"
        />
      </Svg>
      <T size={23} bold>
        {value}
      </T>
    </View>
  );
}
export function Trend({
  values,
  labels,
}: {
  values: number[];
  labels: string[];
}) {
  const c = useTheme(),
    max = Math.max(...values, 1),
    points = values.map((v, i) => [
      8 + (i * 304) / Math.max(values.length - 1, 1),
      90 - (v / max) * 72,
    ]);
  const path = points.map(([x, y], i) => `${i ? "L" : "M"}${x},${y}`).join(" ");
  return (
    <View
      accessible
      accessibilityLabel={labels
        .map((l, i) => `${l}: ₹${values[i]}`)
        .join(", ")}
    >
      <Svg width="100%" height={110} viewBox="0 0 320 110">
        {[20, 55, 90].map((y) => (
          <Line
            key={y}
            x1={0}
            y1={y}
            x2={320}
            y2={y}
            stroke={c.line}
            strokeDasharray="4,4"
          />
        ))}
        <Path
          d={`${path} L312,100 L8,100 Z`}
          fill={c.greenBright}
          opacity={0.08}
        />
        <Path
          d={path}
          stroke={c.greenBright}
          strokeWidth={3}
          fill="none"
          strokeLinejoin="round"
        />
        {points.map(([x, y], i) => (
          <Circle key={i} cx={x} cy={y} r={3} fill={c.greenBright} />
        ))}
      </Svg>
      <View style={{ flexDirection: "row", justifyContent: "space-between" }}>
        {labels.map((l, i) => (
          <T key={i} size={10} muted>
            {l}
          </T>
        ))}
      </View>
    </View>
  );
}
