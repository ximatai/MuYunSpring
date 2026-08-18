/** A business-owned action rendered in the platform drawer title region. */
export interface DrawerTitleAction {
  key: string;
  label: string;
  title?: string;
  emphasis?: 'primary' | 'secondary' | 'quiet';
  intent?: 'normal' | 'danger';
  disabled?: boolean;
  loading?: boolean;
  run(): void | Promise<void>;
}
