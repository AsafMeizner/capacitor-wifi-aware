export interface WifiAwarePlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
